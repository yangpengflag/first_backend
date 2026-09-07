package com.mooc.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mooc.backend.service.WeatherClient;
import com.mooc.backend.service.WeatherFetchResult;
import com.mooc.backend.config.TravelProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 天气读写服务（change: add-travel-services，task 4.4）。
 *
 * <p><b>请求路径不出网</b>。读端只碰 Redis；出网只发生在 {@link WeatherRefreshJob} 里
 * （design §1.1）。降级阶梯比汇率少一级——<b>无快照回填</b>：预报速朽，24h 前的值无存储收益
 * （design §2），阶梯就是「缓存 → null」。
 *
 * <p><b>per-city key</b>（{@code travel:weather:{citySlug}}）：11 个 key，不是一张单例表——
 * 单城刷新失败不该牵连其余城市。
 *
 * <p><b>客户端 bean 可能不存在</b>（key 为空即哨兵，{@code WeatherClient} 按条件不装配），
 * 故经 {@link ObjectProvider} 取用：bean 缺席时读端点直接返 null 且不碰 Redis——客户端不存在
 * 意味着数据从未被写过。
 *
 * <p>时间经注入的 {@link Clock}（照 {@code AuthConfig} 的既有约定），TTL 相关行为才可测。
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /** Redis key 前缀，后接城市 slug（{@code travel:weather:hangzhou} …，design §4）。 */
    public static final String KEY_PREFIX = "travel:weather:";

    /** 瞬时故障告警节流窗口，照 {@code RankingCacheService}：批量一轮 11 城全挂也不刷屏。 */
    private static final long WARN_INTERVAL_MS = 10_000;

    private final StringRedisTemplate redis;
    private final ObjectProvider<WeatherClient> clientProvider;
    private final TravelProperties.Weather config;
    private final Clock clock;
    private volatile long lastWarnMs;

    public WeatherService(StringRedisTemplate redis, ObjectProvider<WeatherClient> clientProvider,
                          TravelProperties props, Clock clock) {
        this.redis = redis;
        this.clientProvider = clientProvider;
        this.config = props.weather();
        this.clock = clock;
    }

    /**
     * 读某城天气。
     *
     * @return 停用 / 客户端未装配 / 无数据 / 超 hard TTL 时为 {@code null}——调用方据此输出
     *         降级响应（天气块整块不渲染）。slug 是否策展由 Controller 层判定（404 语义）
     */
    public WeatherView getWeather(String citySlug) {
        if (!config.enabled() || citySlug == null) {
            return null;
        }
        if (clientProvider.getIfAvailable() == null) {
            return null; // key 空哨兵：客户端不存在 ⇒ 数据从未被写过
        }
        WeatherCacheJson.Payload payload = readCache(KEY_PREFIX + citySlug);
        if (payload == null) {
            return null;
        }
        return toView(payload);
    }

    /** 写一轮成功的拉取结果。天气无快照、单写 Redis，key TTL = hard TTL（物理过期与语义过期对齐）。 */
    public void save(String citySlug, WeatherFetchResult.Success data) {
        writeCache(KEY_PREFIX + citySlug,
                new WeatherCacheJson.Payload(clock.instant(), data.current(), data.forecast()));
    }

    private WeatherCacheJson.Payload readCache(String key) {
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            warnOnce("read", key, e);
            return null; // fail-safe：缓存不成为端点的故障源
        }
        if (raw == null) {
            return null;
        }
        try {
            return WeatherCacheJson.mapper().readValue(raw, WeatherCacheJson.Payload.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("[travel] corrupt weather cache for {} (treat as miss)", key);
            return null;
        }
    }

    private void writeCache(String key, WeatherCacheJson.Payload payload) {
        try {
            redis.opsForValue().set(key, WeatherCacheJson.mapper().writeValueAsString(payload),
                    config.hardTtl());
        } catch (JsonProcessingException e) {
            // 内存对象序列化不应失败；失败属编程错误，但不值得让刷新任务为此崩掉
            log.warn("[travel] failed to serialize weather cache payload");
        } catch (DataAccessException e) {
            warnOnce("write", key, e);
        }
    }

    /**
     * 双 TTL 判定，一律以 {@code fetchedAt} 为准。soft 4h（3h 周期 + 1h 余量，<b>必须严格大于
     * 刷新周期</b>，否则每个健康周期末尾都误报 stale）、hard 24h。
     */
    private WeatherView toView(WeatherCacheJson.Payload payload) {
        if (payload.fetchedAt() == null || payload.current() == null
                || payload.forecast() == null || payload.forecast().isEmpty()) {
            return null;
        }
        Duration age = Duration.between(payload.fetchedAt(), clock.instant());
        if (age.compareTo(config.hardTtl()) > 0) {
            return null; // 超 hard：预报已无意义
        }
        boolean stale = age.compareTo(config.softTtl()) > 0;
        return new WeatherView(payload.fetchedAt(), stale, payload.current(), payload.forecast());
    }

    private void warnOnce(String op, String key, DataAccessException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs >= WARN_INTERVAL_MS) {
            lastWarnMs = now;
            // 不记 e.getMessage()：保持与天气客户端同一条日志卫生底线
            log.warn("[travel] redis {} failed for {} (fail-safe to empty)", op, key);
        }
    }
}
