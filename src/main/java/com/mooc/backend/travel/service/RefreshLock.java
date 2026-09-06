package com.mooc.backend.travel.service;

import com.mooc.backend.travel.config.TravelProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 刷新任务的多实例抢主锁（change: add-travel-services，task 1.4）。
 *
 * <p><b>为什么需要</b>：README 记载「后端 Docker 自托管」，当前应为单实例。一旦扩到 2 个副本，
 * 每个副本都会各自触发 cron —— 上游调用翻倍（OpenWeatherMap 有配额）、并发写同一个 Redis key。
 * Redis 就在手边，用 {@code SETNX key value EX ttl} 抢主，抢不到的实例跳过本轮。
 *
 * <p><b>没有 release 方法，这是设计而非遗漏。</b> 锁靠 TTL 自然过期。主动删锁会废掉 TTL 的
 * 第二个作用——「两轮之间的最小间隔」：多实例间几秒的时钟偏移就足以让第二个实例在第一个刚删锁时
 * 抢到，把这一轮上游调用整个重跑一遍，正是这把锁要防的事。
 *
 * <p><b>TTL 有上下两个界</b>（见 {@link TravelProperties#refreshLockTtl()}）：大于单轮任务最坏耗时
 * （否则锁在任务跑完前就过期），小于最短刷新周期（否则下一轮被自己的锁挡住）。
 *
 * <p>这是本 change 搭的通用件，不是汇率 / 天气各自的私有逻辑，故不带 job 语义、只接 job 名。
 */
@Component
public class RefreshLock {

    /** key 形如 {@code travel:refresh-lock:rates}。 */
    public static final String KEY_PREFIX = "travel:refresh-lock:";

    private static final Logger log = LoggerFactory.getLogger(RefreshLock.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RefreshLock(StringRedisTemplate redis, TravelProperties props) {
        this.redis = redis;
        this.ttl = props.refreshLockTtl();
    }

    /**
     * 尝试拿到某个 job 本轮的执行权。
     *
     * <p>Redis 故障时返回 {@code false}（跳过本轮），<b>方向与缓存读的 fail-safe 相反</b>，这是有意的：
     * 缓存读失败降级去查库、结果依然正确；抢锁失败若降级成「当作拿到」，多实例下就变成谁都能跑。
     * 跳过的代价只是数据晚一个 tick，且有旧值兜底。
     *
     * @param job 任务名，取 {@code rates} / {@code weather}
     * @return 拿到锁才返回 {@code true}
     */
    public boolean tryAcquire(String job) {
        String key = KEY_PREFIX + job;
        try {
            // value 只为可观测性（redis-cli GET 能看出是谁、什么时候抢到的），不参与判定。
            String owner = Long.toString(System.currentTimeMillis());
            // null 出现在连接被回收等边缘情况，按「没拿到」处理。
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, ttl));
        } catch (DataAccessException e) {
            log.warn("[travel] redis unavailable while acquiring {} (skip this round): {}", key, e.getMessage());
            return false;
        }
    }
}
