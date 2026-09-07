package com.mooc.backend.service;
import com.mooc.backend.service.WeatherService;
import com.mooc.backend.service.WeatherView;

import com.mooc.backend.service.CurrentWeather;
import com.mooc.backend.service.ForecastBucket;
import com.mooc.backend.service.WeatherClient;
import com.mooc.backend.service.WeatherFetchResult;
import com.mooc.backend.config.TravelProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 天气读服务单元测试（change: add-travel-services，task 4.3）。
 *
 * <p>照 {@link ExchangeRateServiceTest} 的 Mockito 风格。与汇率的三处结构性差异，各有对应测试：
 *
 * <ul>
 *   <li><b>per-city key</b>（{@code travel:weather:{slug}}）——11 个 key，不是一张单例表；</li>
 *   <li><b>无快照回填</b>——预报速朽，24h 前的值无存储收益，降级阶梯只有两级：缓存 → null；</li>
 *   <li><b>双 TTL 窗口不同</b>（soft 4h / hard 24h）——值速朽程度高，余量按 3h 周期 + 1h 算。</li>
 * </ul>
 *
 * <p><b>请求路径不出网</b>（spec「缓存 miss 不触发外部调用」）：读路径不持有 HTTP 客户端，
 * miss 一律返 null——第四条测试把它钉死。
 */
class WeatherServiceTest {

    private static final String KEY = "travel:weather:hangzhou";
    private static final Instant NOON = Instant.parse("2026-09-06T12:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectProvider<WeatherClient> clientProvider;
    private WeatherClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        clientProvider = mock(ObjectProvider.class);
        client = mock(WeatherClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(client);
    }

    /** 缓存命中：current 与 forecast 一起回来，未超 soft TTL 不标 stale。 */
    @Test
    void readsPerCityCacheKey() {
        // fetchedAt 距 now 3h < soft 4h：新鲜
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-09-06T09:00:00Z"));
        WeatherService service = service(NOON);

        WeatherView view = service.getWeather("hangzhou");

        assertThat(view).isNotNull();
        assertThat(view.fetchedAt()).isEqualTo(Instant.parse("2026-09-06T09:00:00Z"));
        assertThat(view.stale()).isFalse();
        assertThat(view.current().tempC()).isEqualTo(18.4);
        assertThat(view.current().description()).isEqualTo("overcast clouds");
        assertThat(view.current().humidity()).isEqualTo(72);
        assertThat(view.current().windSpeed()).isEqualTo(3.1);
        assertThat(view.forecast()).hasSize(1);
        assertThat(view.forecast().get(0).dt()).isEqualTo(1757046000L);
        assertThat(view.forecast().get(0).tempMinC()).isEqualTo(16.1);
        assertThat(view.forecast().get(0).condition()).isEqualTo("Clouds");
    }

    /**
     * key 按 slug 隔离：读 hangzhou 不会命中别的城市的条目——per-city key 的本意。
     */
    @Test
    void doesNotReadAnotherCitysKey() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(service(NOON).getWeather("hangzhou")).isNull();

        verify(valueOps).get(KEY);
        verify(valueOps, never()).get(eq("travel:weather:beijing"));
    }

    /** 超 soft TTL（4h）：仍供旧值 + stale=true。刷新失败一轮不该让天气块消失。 */
    @Test
    void servesStaleValuesBeyondSoftTtl() {
        // fetchedAt 距 now 6h > soft 4h，仍 < hard 24h
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-09-06T06:00:00Z"));

        WeatherView view = service(Instant.parse("2026-09-06T18:00:00Z")).getWeather("hangzhou");

        assertThat(view).isNotNull();
        assertThat(view.stale()).isTrue();
        assertThat(view.current()).isNotNull();
    }

    /** 超 hard TTL（24h）：返 null。速朽数据没有「旧但仍可参考」的区间。 */
    @Test
    void returnsNullBeyondHardTtl() {
        when(valueOps.get(KEY)).thenReturn(cacheJson("2026-09-05T06:00:00Z"));

        assertThat(service(NOON).getWeather("hangzhou")).isNull();
    }

    /**
     * 缓存 miss 直接返 null，<b>不回源上游</b>（spec「请求路径不出网」）：读路径根本不该调用
     * HTTP 客户端，延迟上界 = Redis 超时（500ms），与 OWM 可用性无关。
     */
    @Test
    void cacheMissReturnsNullWithoutUpstreamCall() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(service(NOON).getWeather("hangzhou")).isNull();

        verifyNoInteractions(client);
    }

    /**
     * key 为空（客户端 bean 未装配）：读端点返 null 且不碰 Redis——客户端不存在 ⇒ 数据从未
     * 被写过，查了也白查。key 非空但值持续 401 是另一态，由刷新任务的告警覆盖。
     */
    @Test
    void returnsNullWhenClientNotAssembled() {
        when(clientProvider.getIfAvailable()).thenReturn(null);
        WeatherService service = service(NOON);

        assertThat(service.getWeather("hangzhou")).isNull();

        verify(valueOps, never()).get(anyString());
    }

    /** enabled=false 语义为「功能关停」：读路径完全不碰 Redis。 */
    @Test
    void disabledReadsNothing() {
        WeatherService service = new WeatherService(redis, clientProvider, props(false),
                Clock.fixed(NOON, ZoneOffset.UTC));

        assertThat(service.getWeather("hangzhou")).isNull();

        verify(valueOps, never()).get(anyString());
    }

    /** Redis 抛 {@code DataAccessException}：fail-safe 返 null，不向客户端抛 5xx。 */
    @Test
    void failsSafeWhenRedisThrows() {
        when(valueOps.get(KEY)).thenThrow(new QueryTimeoutException("redis down"));

        assertThat(service(NOON).getWeather("hangzhou")).isNull();
    }

    /** 缓存内容损坏当 miss（无快照可退，结果就是 null，而不是异常或半份数据）。 */
    @Test
    void treatsCorruptCacheAsMiss() {
        when(valueOps.get(KEY)).thenReturn("{not json");

        assertThat(service(NOON).getWeather("hangzhou")).isNull();
    }

    /** 写入：per-city key + TTL = hard TTL（物理过期与语义过期对齐，design §2）。 */
    @Test
    void savesToPerCityKeyWithHardTtl() {
        WeatherFetchResult.Success data = success();

        service(NOON).save("hangzhou", data);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(KEY), json.capture(), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(24));
        // 缓存字节格式要能人眼排查（redis-cli GET 直接可读），时间戳用 ISO-8601 文本
        assertThat(json.getValue()).contains("\"fetched_at\":\"2026-09-06T12:00:00Z\"");
        assertThat(json.getValue()).contains("\"temp_c\":18.4");
        assertThat(json.getValue()).doesNotContain("stale"); // stale 是读取时刻的派生值，不进缓存
    }

    /** 写路径 Redis 故障不抛穿：下次刷新自然会覆盖，调度线程不受影响。 */
    @Test
    void swallowsRedisWriteFailureOnSave() {
        org.mockito.Mockito.doThrow(new QueryTimeoutException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        service(NOON).save("hangzhou", success());
    }

    private WeatherService service(Instant now) {
        return new WeatherService(redis, clientProvider, props(true), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static WeatherFetchResult.Success success() {
        return new WeatherFetchResult.Success(
                new CurrentWeather(18.4, "overcast clouds", "04d", 72, 3.1),
                List.of(new ForecastBucket(1757046000L, 16.1, 20.2, "Clouds", "overcast clouds", "04d")));
    }

    private static TravelProperties props(boolean enabled) {
        return new TravelProperties(
                new TravelProperties.ExchangeRate(true, "CNY", "0 30 3 * * *",
                        Duration.ofHours(26), Duration.ofDays(7)),
                new TravelProperties.Weather(enabled, "test-key", "0 0 */3 * * *", "en",
                        Duration.ofHours(4), Duration.ofHours(24)),
                new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofMinutes(5));
    }

    private static String cacheJson(String fetchedAt) {
        return """
                {"fetched_at":"%s",
                 "current":{"temp_c":18.4,"description":"overcast clouds","icon":"04d","humidity":72,"wind_speed":3.1},
                 "forecast":[{"dt":1757046000,"temp_min_c":16.1,"temp_max_c":20.2,
                              "condition":"Clouds","description":"overcast clouds","icon":"04d"}]}
                """.formatted(fetchedAt);
    }
}
