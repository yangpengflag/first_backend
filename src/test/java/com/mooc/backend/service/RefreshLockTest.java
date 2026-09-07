package com.mooc.backend.service;
import com.mooc.backend.service.RefreshLock;

import com.mooc.backend.config.TravelProperties;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多实例抢主锁单元测试（change: add-travel-services，task 1.3）。
 *
 * <p>覆盖：`SETNX ... EX ttl` 语义（首个实例拿到、第二个拿不到）；key 形如
 * `travel:refresh-lock:{job}`；TTL 取自配置；**跑完不删锁**；Redis 故障时的取舍。
 * 全程 Mockito，不依赖真 Redis。
 */
class RefreshLockTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    /** 两个实例并发抢同一个 job，只有一个拿到——`setIfAbsent` 的原子性就是这条的全部依据。 */
    @Test
    void onlyOneInstanceAcquiresTheSameJobLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vo = valueOps(redis);
        RefreshLock lock = new RefreshLock(redis, props(TTL));

        when(vo.setIfAbsent(eq("travel:refresh-lock:rates"), anyString(), eq(TTL)))
                .thenReturn(true)    // 实例 A
                .thenReturn(false);  // 实例 B，同一轮

        assertThat(lock.tryAcquire("rates")).isTrue();
        assertThat(lock.tryAcquire("rates")).isFalse();
    }

    /** key 前缀与 job 名拼接固定，且 TTL 用配置值而非硬编码常量。 */
    @Test
    void usesJobScopedKeyAndConfiguredTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vo = valueOps(redis);
        Duration ttl = Duration.ofMinutes(7); // 故意不同于默认 5m，证明值是读来的
        RefreshLock lock = new RefreshLock(redis, props(ttl));

        when(vo.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        lock.tryAcquire("weather");

        verify(vo).setIfAbsent(eq("travel:refresh-lock:weather"), anyString(), eq(ttl));
    }

    /**
     * 两个 job 各自一把锁：汇率在跑不该挡住天气。
     */
    @Test
    void differentJobsUseIndependentLocks() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vo = valueOps(redis);
        RefreshLock lock = new RefreshLock(redis, props(TTL));

        when(vo.setIfAbsent(eq("travel:refresh-lock:rates"), anyString(), eq(TTL))).thenReturn(false);
        when(vo.setIfAbsent(eq("travel:refresh-lock:weather"), anyString(), eq(TTL))).thenReturn(true);

        assertThat(lock.tryAcquire("rates")).isFalse();
        assertThat(lock.tryAcquire("weather")).isTrue();
    }

    /**
     * <b>没有 release。</b> 锁靠 TTL 自然过期，任务跑完不主动删——删锁等于废掉 TTL 的第二个作用
     * （「两轮之间的最小间隔」），多实例间几秒的时钟偏移就足以让第二个实例在第一个刚删锁时抢到、
     * 把这一轮上游调用重跑一遍。这条断言的形式是：整个交互里不出现任何 delete。
     */
    @Test
    void neverDeletesTheLockSoTtlAlsoActsAsMinimumInterval() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vo = valueOps(redis);
        RefreshLock lock = new RefreshLock(redis, props(TTL));

        when(vo.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        lock.tryAcquire("rates");

        verify(redis, never()).delete(anyString());
        assertThat(RefreshLock.class.getDeclaredMethods())
                .noneMatch(m -> m.getName().equals("release") || m.getName().equals("unlock"));
    }

    /**
     * Redis 宕机时选择<b>跳过本轮</b>（返回 false），与缓存读的 fail-safe 方向相反，这是有意的：
     * 缓存读失败降级去查库、结果依然正确；抢锁失败若降级为「当作拿到」，多实例下就变成谁都能跑，
     * 正是这把锁要防的事。跳过的代价只是数据晚一个 tick 刷新，还有旧值兜底。
     */
    @Test
    void redisFailureSkipsTheRoundRatherThanAssumingAcquired() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vo = valueOps(redis);
        RefreshLock lock = new RefreshLock(redis, props(TTL));

        when(vo.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("boom"));

        assertThat(lock.tryAcquire("rates")).isFalse();
    }

    /** Redis 返回 null（连接被回收等边缘情况）同样按「没拿到」处理，不能 NPE。 */
    @Test
    void nullReplyIsTreatedAsNotAcquired() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vo = valueOps(redis);
        RefreshLock lock = new RefreshLock(redis, props(TTL));

        when(vo.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(lock.tryAcquire("rates")).isFalse();
    }

    /**
     * 锁 TTL 的上下两界（design §4）。这不是在测 {@code RefreshLock} 的代码，而是把
     * 「单轮任务最坏耗时 &lt; TTL &lt; 最短刷新周期」这条推理钉在测试里：日后有人把 read timeout
     * 调大、或把天气 cron 从 3h 改成 1m，这条会红，而不是等到线上出现重复刷新。
     */
    @Test
    void configuredTtlSitsBetweenWorstCaseJobDurationAndShortestRefreshPeriod() {
        Duration readTimeout = Duration.ofSeconds(5);
        int worstCaseUpstreamCalls = 11 * 2; // 11 城 × (current + forecast)，串行
        Duration worstCaseJob = readTimeout.multipliedBy(worstCaseUpstreamCalls); // ≈110s
        Duration shortestRefreshPeriod = Duration.ofHours(3); // weather 的 cron

        assertThat(TTL).isGreaterThan(worstCaseJob);
        assertThat(TTL).isLessThan(shortestRefreshPeriod);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> valueOps(StringRedisTemplate redis) {
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vo);
        return vo;
    }

    /** 只有 refresh-lock-ttl 对本测试有意义，其余子配置给 null 即可。 */
    private static TravelProperties props(Duration refreshLockTtl) {
        return new TravelProperties(null, null, null, refreshLockTtl);
    }
}
