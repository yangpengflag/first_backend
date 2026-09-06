package com.mooc.backend.travel.service;

import com.mooc.backend.travel.client.ExchangeRateSnapshotData;
import com.mooc.backend.travel.config.TravelProperties;
import com.mooc.backend.travel.domain.ExchangeRateSnapshot;
import com.mooc.backend.travel.repository.ExchangeRateSnapshotRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 汇率读服务单元测试（change: add-travel-services，task 3.3）。
 *
 * <p>照 {@code RankingCacheServiceTest} 的 Mockito 风格（mock {@code StringRedisTemplate} +
 * {@code ValueOperations}）。时间经注入的 {@code Clock} 固定，TTL 判定才可断言。
 *
 * <p>七条分支覆盖 design §2 的降级阶梯：**缓存命中**（不碰 DB）→ **Redis 空则从快照回填**
 * （冷启动后功能不消失，这正是那张单行表存在的唯一理由）→ **超 soft 供旧值 + `stale=true`**
 * （诚实标注，不是消失）→ **超 hard 返 null**（三天前的汇率不再是量级参考）→
 * **Redis 抛 {@code DataAccessException} 时 fail-safe 到快照**（缓存不成为端点的故障源）→
 * **缓存内容损坏当 miss**（照 `RankingCacheService`）→ **停用时完全不碰 Redis 与 DB**。
 *
 * <p>还有一条最容易写错的：**`fetched_at` 是今日、`upstream_date` 是上周五时不判 stale**。
 * ECB 周末与假日不发布，周日刷新会成功拿到周五的牌价——数据是新鲜的，牌价是三天前的。
 * TTL 判定用 {@code fetched_at}，展示日期用 {@code upstream_date}（design §2.1）。
 */
class ExchangeRateServiceTest {

    private static final String KEY = "travel:rates:CNY";

    /** 周日 12:00 UTC——用它配上周五的 upstreamDate 复现 ECB 不发布的周末。 */
    private static final Instant SUNDAY_NOON = Instant.parse("2026-09-06T12:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ExchangeRateSnapshotRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        repository = mock(ExchangeRateSnapshotRepository.class);
    }

    /** 缓存命中：直接返回，**完全不碰 DB**（否则这层缓存等于没有）。 */
    @Test
    void returnsCachedRatesWithoutTouchingDatabase() {
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-09-04", "2026-09-05T03:30:12Z"));
        ExchangeRateService service = service(SUNDAY_NOON);

        ExchangeRateView view = service.getRates();

        assertThat(view).isNotNull();
        assertThat(view.base()).isEqualTo("CNY");
        assertThat(view.asOf()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(view.fetchedAt()).isEqualTo(Instant.parse("2026-09-05T03:30:12Z"));
        assertThat(view.rates()).containsEntry("USD", 0.14901).hasSize(2);
        verifyNoInteractions(repository);
    }

    /**
     * Redis 空 → 从快照表回填。这是那张单行表存在的<b>唯一</b>理由：Redis 重启即空，
     * 汇率影响全站每一个价格展示，不该随缓存一起消失。回填后顺手写回 Redis，下次即命中。
     */
    @Test
    void fallsBackToSnapshotAndBackfillsCacheWhenRedisIsEmpty() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID))
                .thenReturn(Optional.of(snapshot("2026-09-04", "2026-09-05T03:30:12Z")));
        ExchangeRateService service = service(SUNDAY_NOON);

        ExchangeRateView view = service.getRates();

        assertThat(view).isNotNull();
        assertThat(view.asOf()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(view.rates()).containsEntry("USD", 0.14901);
        verify(valueOps).set(eq(KEY), anyString(), any(Duration.class));
    }

    /**
     * 超 soft TTL（26h）：**继续供旧值**并置 {@code stale=true}，不返 null。
     * 汇率速朽程度低，3 天前的仍可作量级参考；UI 靠这个布尔加「数据可能已过时」提示。
     */
    @Test
    void servesStaleValuesBeyondSoftTtl() {
        // fetchedAt 距 now 27h > soft 26h，仍 < hard 7d
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-09-04", "2026-09-05T09:00:00Z"));
        ExchangeRateService service = service(SUNDAY_NOON);

        ExchangeRateView view = service.getRates();

        assertThat(view).isNotNull();
        assertThat(view.stale()).isTrue();
        assertThat(view.rates()).isNotEmpty();
    }

    /** soft TTL 内：不标 stale。取余量的意义就在这里——一次刷新失败才进 stale，不是每天一半时间。 */
    @Test
    void doesNotMarkFreshValuesStale() {
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-09-04", "2026-09-06T03:30:00Z"));

        ExchangeRateView view = service(SUNDAY_NOON).getRates();

        assertThat(view).isNotNull();
        assertThat(view.stale()).isFalse();
    }

    /**
     * <b>本测试类最容易写错的一条。</b> {@code fetched_at} 是今天（8.5h 前，soft TTL 内），
     * 而 {@code upstream_date} 是上周五（3 天前）——ECB 周末与假日不发布，周日刷新就是这个形状。
     * 数据是新鲜的、牌价是三天前的：**TTL 判定取 `fetched_at` 故不 stale**，
     * 展示日期取 `upstream_date` 故是周五。拿 {@code upstream_date} 判 TTL 会让每个周末都误报。
     */
    @Test
    void doesNotMarkStaleWhenUpstreamDateIsOldButFetchIsRecent() {
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-09-04", "2026-09-06T03:30:00Z"));

        ExchangeRateView view = service(SUNDAY_NOON).getRates();

        assertThat(view).isNotNull();
        assertThat(view.stale()).as("fetched 8.5h ago is within the 26h soft TTL").isFalse();
        assertThat(view.asOf()).as("display date is the upstream rate date, not today")
                .isEqualTo(LocalDate.of(2026, 9, 4));
    }

    /** 超 hard TTL（7d）：返 null，端点据此输出全 null 降级响应。 */
    @Test
    void returnsNullBeyondHardTtl() {
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-08-20", "2026-08-21T03:30:00Z"));
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID))
                .thenReturn(Optional.of(snapshot("2026-08-20", "2026-08-21T03:30:00Z")));

        assertThat(service(SUNDAY_NOON).getRates()).isNull();
    }

    /**
     * Redis 抛 {@code DataAccessException}：fail-safe 到快照表，不让缓存成为端点的故障源
     * （照 {@code RankingCacheService} 的既有做法）。
     */
    @Test
    void failsSafeToSnapshotWhenRedisThrows() {
        when(valueOps.get(KEY)).thenThrow(new QueryTimeoutException("redis down"));
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID))
                .thenReturn(Optional.of(snapshot("2026-09-04", "2026-09-06T03:30:00Z")));

        ExchangeRateView view = service(SUNDAY_NOON).getRates();

        assertThat(view).isNotNull();
        assertThat(view.rates()).containsEntry("USD", 0.14901);
    }

    /** Redis 写也不能抛穿：回填失败只是下次再 miss 一遍。 */
    @Test
    void swallowsRedisWriteFailureDuringBackfill() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID))
                .thenReturn(Optional.of(snapshot("2026-09-04", "2026-09-06T03:30:00Z")));
        org.mockito.Mockito.doThrow(new QueryTimeoutException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThat(service(SUNDAY_NOON).getRates()).isNotNull();
    }

    /** 缓存内容损坏（上游格式变更 / 手工改键）当 miss 处理，不抛异常（照 `RankingCacheService`）。 */
    @Test
    void treatsCorruptCacheAsMiss() {
        when(valueOps.get(KEY)).thenReturn("{not json");
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID))
                .thenReturn(Optional.of(snapshot("2026-09-04", "2026-09-06T03:30:00Z")));

        assertThat(service(SUNDAY_NOON).getRates()).isNotNull();
    }

    /** Redis 与快照皆无（真正的冷启动）：返 null。 */
    @Test
    void returnsNullWhenNeitherCacheNorSnapshotHasValues() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThat(service(SUNDAY_NOON).getRates()).isNull();
    }

    /**
     * {@code enabled=false} 的语义是「功能关停」，**不是**「绕过缓存照样正确」（design §2）：
     * 端点返回 null、UI 不渲染该块。故读路径完全不碰 Redis 与 DB。
     */
    @Test
    void disabledReadsNeitherRedisNorDatabase() {
        ExchangeRateService service = new ExchangeRateService(
                redis, repository, props(false), Clock.fixed(SUNDAY_NOON, ZoneOffset.UTC));

        assertThat(service.getRates()).isNull();

        verify(valueOps, never()).get(anyString());
        verifyNoInteractions(repository);
    }

    /**
     * <b>写顺序：先快照，后 Redis</b>（design §2.1）。快照是持久兜底、Redis 是可重建的读缓存：
     * 先持久层则「Redis 写失败」退化成一次 miss；反序会留下 Redis 有值、重启后快照仍是旧值的不一致。
     */
    @Test
    void writesSnapshotBeforeRedis() {
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID)).thenReturn(Optional.empty());
        ExchangeRateService service = service(SUNDAY_NOON);

        service.save(new ExchangeRateSnapshotData("CNY", LocalDate.of(2026, 9, 4),
                Map.of("USD", 0.14901, "JPY", 23.283)));

        InOrder order = inOrder(repository, valueOps);
        order.verify(repository).save(any(ExchangeRateSnapshot.class));
        order.verify(valueOps).set(eq(KEY), anyString(), any(Duration.class));
    }

    /** 已有快照时是**覆盖写同一行**，不插新行——单行表，不存历史。 */
    @Test
    void overwritesTheSingleSnapshotRowInsteadOfInserting() {
        ExchangeRateSnapshot existing = snapshot("2026-08-28", "2026-08-28T03:30:00Z");
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID)).thenReturn(Optional.of(existing));
        ExchangeRateService service = service(SUNDAY_NOON);

        service.save(new ExchangeRateSnapshotData("CNY", LocalDate.of(2026, 9, 4), Map.of("USD", 0.14901)));

        ArgumentCaptor<ExchangeRateSnapshot> saved = ArgumentCaptor.forClass(ExchangeRateSnapshot.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getId()).isEqualTo(ExchangeRateSnapshot.SINGLETON_ID);
        assertThat(saved.getValue().getUpstreamDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(saved.getValue().getFetchedAt()).isEqualTo(SUNDAY_NOON);
    }

    /** Redis key 的 TTL = hard TTL，让物理过期与语义过期对齐（design §2）。 */
    @Test
    void redisKeyTtlEqualsHardTtl() {
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID)).thenReturn(Optional.empty());

        service(SUNDAY_NOON).save(new ExchangeRateSnapshotData(
                "CNY", LocalDate.of(2026, 9, 4), Map.of("USD", 0.14901)));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(KEY), anyString(), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofDays(7));
    }

    /** 写路径的 Redis 故障同样不抛穿：快照已落地，下次读从快照回填。 */
    @Test
    void swallowsRedisFailureOnSave() {
        when(repository.findById(ExchangeRateSnapshot.SINGLETON_ID)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new QueryTimeoutException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        service(SUNDAY_NOON).save(new ExchangeRateSnapshotData(
                "CNY", LocalDate.of(2026, 9, 4), Map.of("USD", 0.14901)));

        verify(repository).save(any(ExchangeRateSnapshot.class));
    }

    private ExchangeRateService service(Instant now) {
        return new ExchangeRateService(redis, repository, props(true), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static TravelProperties props(boolean enabled) {
        return new TravelProperties(
                new TravelProperties.ExchangeRate(enabled, "CNY", "0 30 3 * * *",
                        Duration.ofHours(26), Duration.ofDays(7)),
                null,
                new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofMinutes(5));
    }

    private static String cacheJson(String upstreamDate, String fetchedAt) {
        return """
                {"base":"CNY","upstream_date":"%s","fetched_at":"%s",
                 "rates":{"USD":0.14901,"JPY":23.283}}
                """.formatted(upstreamDate, fetchedAt);
    }

    private static ExchangeRateSnapshot snapshot(String upstreamDate, String fetchedAt) {
        return ExchangeRateSnapshot.create("CNY", LocalDate.parse(upstreamDate), Instant.parse(fetchedAt),
                "{\"USD\":0.14901,\"JPY\":23.283}", Instant.parse(fetchedAt));
    }
}
