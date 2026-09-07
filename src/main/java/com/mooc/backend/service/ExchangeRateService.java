package com.mooc.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.service.ExchangeRateSnapshotData;
import com.mooc.backend.config.TravelProperties;
import com.mooc.backend.entity.ExchangeRateSnapshot;
import com.mooc.backend.repository.ExchangeRateSnapshotRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 汇率读写服务（change: add-travel-services，task 3.4）。
 *
 * <p><b>请求路径不出网。</b> 读端只碰 Redis 与快照表；出网只发生在刷新任务里（design §1.1）。
 * 手写 Cache-Aside（`StringRedisTemplate`）而非 `@Cacheable`——栈内无 starter-cache，
 * 照 {@code RankingCacheService} 的既有模式。
 *
 * <p><b>三级降级阶梯</b>（design §2）：
 * <ol>
 *   <li>Redis 命中 → 直接返回（不碰 DB）</li>
 *   <li>Redis 空 / 损坏 / 抛异常 → 读快照表并回填 Redis（这是那张单行表存在的唯一理由：
 *       Redis 重启即空，而汇率影响全站每一个价格展示）</li>
 *   <li>两者皆无 / 超 hard TTL / 功能停用 → 返回 {@code null}，端点输出全 null 的 200</li>
 * </ol>
 *
 * <p><b>soft 与 hard 的区别不是程度而是种类</b>：超 soft 仍供旧值、只置 {@code stale=true}
 * （汇率速朽程度低，3 天前的仍是量级参考，UI 加个提示即可）；超 hard 才返 null。
 * TTL 判定<b>一律用 {@code fetchedAt}</b>，绝不用 {@code upstreamDate}——ECB 周末不发布，
 * 用后者会让每个周末都误报 stale（design §2.1）。
 *
 * <p><b>{@code enabled=false} 的语义是「功能关停」</b>，不是 {@code app.ranking-cache.enabled=false}
 * 那种「绕过缓存照样正确」：外部数据没有这个奢侈。停用时读路径完全不碰 Redis 与 DB。
 *
 * <p>时间经注入的 {@link Clock}（照 {@code AuthConfig} 的既有约定），TTL 相关行为才可测。
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    /** Redis key 前缀；整表一个 key，**不按目标币种拆分**（币种切换是纯客户端格式化）。 */
    public static final String KEY_PREFIX = "travel:rates:";

    /** 告警节流窗口，照 {@code RankingCacheService}：Redis 宕机期不刷日志。 */
    private static final long WARN_INTERVAL_MS = 10_000;

    private static final ObjectMapper MAPPER = ExchangeRateCacheJson.mapper();

    private final StringRedisTemplate redis;
    private final ExchangeRateSnapshotRepository repository;
    private final TravelProperties.ExchangeRate config;
    private final Clock clock;
    private final String key;
    private volatile long lastWarnMs;

    public ExchangeRateService(StringRedisTemplate redis, ExchangeRateSnapshotRepository repository,
                               TravelProperties props, Clock clock) {
        this.redis = redis;
        this.repository = repository;
        this.config = props.exchangeRate();
        this.clock = clock;
        this.key = KEY_PREFIX + config.base();
    }

    /**
     * 读当前汇率。
     *
     * @return 停用 / 无数据 / 超 hard TTL 时为 {@code null}——调用方据此输出降级响应
     */
    public ExchangeRateView getRates() {
        if (!config.enabled()) {
            return null;
        }
        ExchangeRateCacheJson.Payload cached = readCache();
        if (cached != null) {
            return toView(cached);
        }
        return readSnapshotAndBackfill();
    }

    /**
     * 写一轮成功的拉取结果。<b>先快照，后 Redis</b>（design §2.1）：快照是持久兜底、Redis 是
     * 可重建的读缓存，先持久层则「Redis 写失败」退化成一次 miss；反序会留下 Redis 有值、
     * 重启后快照仍是旧值的不一致。
     */
    @Transactional
    public void save(ExchangeRateSnapshotData data) {
        Instant now = clock.instant();
        String ratesJson = serializeRates(data.rates());

        // 1) 持久层：单行覆盖写（主键固定，不存历史）
        ExchangeRateSnapshot snapshot = repository.findById(ExchangeRateSnapshot.SINGLETON_ID)
                .map(existing -> {
                    existing.overwrite(data.base(), data.upstreamDate(), now, ratesJson, now);
                    return existing;
                })
                .orElseGet(() -> ExchangeRateSnapshot.create(
                        data.base(), data.upstreamDate(), now, ratesJson, now));
        repository.save(snapshot);

        // 2) 读缓存：失败只是下次读走快照回填，不能让它回滚已落地的快照
        writeCache(new ExchangeRateCacheJson.Payload(data.base(), data.upstreamDate(), now, data.rates()));
    }

    private ExchangeRateCacheJson.Payload readCache() {
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            warnOnce("read", e);
            return null; // fail-safe：落到快照路径，缓存不成为端点的故障源
        }
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, ExchangeRateCacheJson.Payload.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("[travel] corrupt rates cache for {} (treat as miss): {}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(ExchangeRateCacheJson.Payload payload) {
        try {
            // key TTL = hard TTL：让物理过期与语义过期对齐——Redis 里存在的键一定还在可用窗口内
            redis.opsForValue().set(key, MAPPER.writeValueAsString(payload), config.hardTtl());
        } catch (JsonProcessingException e) {
            // 内存对象序列化不应失败；失败属编程错误，但不值得让刷新任务或读请求为此崩掉
            log.warn("[travel] failed to serialize rates cache payload: {}", e.getMessage());
        } catch (DataAccessException e) {
            warnOnce("write", e);
        }
    }

    private ExchangeRateView readSnapshotAndBackfill() {
        Optional<ExchangeRateSnapshot> found = repository.findById(ExchangeRateSnapshot.SINGLETON_ID);
        if (found.isEmpty()) {
            return null; // 真正的冷启动：还没成功拉过一次
        }
        ExchangeRateSnapshot snapshot = found.get();
        Map<String, Double> rates = deserializeRates(snapshot.getRatesJson());
        if (rates == null || rates.isEmpty()) {
            log.warn("[travel] snapshot rates_json unusable (treat as no data)");
            return null;
        }
        ExchangeRateCacheJson.Payload payload = new ExchangeRateCacheJson.Payload(
                snapshot.getBase(), snapshot.getUpstreamDate(), snapshot.getFetchedAt(), rates);
        ExchangeRateView view = toView(payload);
        if (view != null) {
            // 只在数据仍可用时回填：把一份超 hard TTL 的值写回 Redis 毫无意义
            writeCache(payload);
        }
        return view;
    }

    /**
     * 把缓存 / 快照的载荷折成读结果，顺手做 TTL 判定。
     *
     * <p>两个 TTL 都以 {@code fetchedAt} 为准：{@code upstreamDate} 是牌价所属日期，
     * ECB 周末与假日不发布，拿它判 TTL 会让每个周末都误报 stale。
     */
    private ExchangeRateView toView(ExchangeRateCacheJson.Payload payload) {
        if (payload.fetchedAt() == null || payload.rates() == null || payload.rates().isEmpty()) {
            return null;
        }
        Duration age = Duration.between(payload.fetchedAt(), clock.instant());
        if (age.compareTo(config.hardTtl()) > 0) {
            return null; // 超 hard：不再是量级参考
        }
        boolean stale = age.compareTo(config.softTtl()) > 0;
        LocalDate asOf = payload.upstreamDate();
        return new ExchangeRateView(payload.base(), asOf, payload.fetchedAt(), stale,
                Map.copyOf(payload.rates()));
    }

    private static String serializeRates(Map<String, Double> rates) {
        try {
            return MAPPER.writeValueAsString(rates);
        } catch (JsonProcessingException e) {
            // 一个 Map<String, Double> 序列化失败属编程错误，向上抛由全局异常处理兜底
            throw new IllegalStateException("Failed to serialize exchange rates", e);
        }
    }

    private static Map<String, Double> deserializeRates(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() { });
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("[travel] corrupt snapshot rates_json: {}", e.getMessage());
            return null;
        }
    }

    private void warnOnce(String op, DataAccessException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs >= WARN_INTERVAL_MS) {
            lastWarnMs = now;
            log.warn("[travel] redis {} failed for {} (fail-safe to snapshot): {}", op, key, e.getMessage());
        }
    }
}
