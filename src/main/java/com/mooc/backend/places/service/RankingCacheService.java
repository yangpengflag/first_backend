package com.mooc.backend.places.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.places.api.SpotSummary;
import com.mooc.backend.places.repository.SpotRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 景点排行榜 Redis 缓存（Cache-Aside，change: add-spot-ranking-redis-cache）。
 *
 * <p>key 格式 {@code spot:ranking:{type}}（type 为归一化值：非法 / null 回退 {@code popular}）。
 * 每个 key 缓存该 type 的<b>Top 50 规范快照</b>（有序前缀，Top50 截断即任意 TopN），命中时按请求
 * {@code limit} 切片，避免 key 里拼 limit 造成的串档与写放大。TTL 5 分钟（300s）。
 *
 * <p>新鲜度：缓存命中数据最多滞后一个 TTL；景点写操作（create/update）与收藏切换由调用方在提交后
 * 触发 {@link #evictAll()} / {@link #evictBookmarks()}，令下个请求即最新。浏览量变化<b>不</b>失效缓存
 * （否则命中率归零），`popular` 榜由 TTL 保鲜。
 *
 * <p>降级：{@code app.ranking-cache.enabled=false} 时完全不碰 Redis、按请求 {@code limit} 精确回源
 * （与原实现逐字节等价）；任何 Redis 操作抛 {@link DataAccessException} 都 catch 后 fail-safe 直查 DB——
 * 缓存不成为排行榜端点的故障源。序列化经 {@link SpotSummaryCacheJson}（独立 mapper，剔除 request_id，
 * 命中对象按当次请求 MDC 重建关联 ID）。
 */
@Service
public class RankingCacheService {

    private static final Logger log = LoggerFactory.getLogger(RankingCacheService.class);

    /** 排行榜端点 key 前缀。 */
    public static final String KEY_PREFIX = "spot:ranking:";
    /** 每 key 缓存的规范 Top N（端点 limit 上限）。 */
    private static final int TOP_N_CACHE = 50;
    /** 缓存 TTL。 */
    private static final Duration TTL = Duration.ofSeconds(300);
    /** 告警节流窗口（毫秒），避免 Redis 宕机期每个请求刷一条 warn。 */
    private static final long WARN_INTERVAL_MS = 10_000;

    private static final Set<String> TYPES = Set.of("rating", "popular", "bookmarks");

    /** 缓存专用序列化器（见 {@link SpotSummaryCacheJson}：独立 mapper + 忽略 request_id）。 */
    private static final ObjectMapper MAPPER = SpotSummaryCacheJson.mapper();

    private final SpotRepository spotRepository;
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private volatile long lastWarnMs;

    public RankingCacheService(SpotRepository spotRepository, StringRedisTemplate redis,
                               @Value("${app.ranking-cache.enabled:true}") boolean enabled) {
        this.spotRepository = spotRepository;
        this.redis = redis;
        this.enabled = enabled;
    }

    /**
     * 读取排行榜：命中返回缓存切片；未命中查 DB（启用态查 Top50 规范回源并写缓存）后返回。
     *
     * @param type  排行 type（非法回退 popular）
     * @param limit 请求条数，钳制到 [1,50]
     */
    public List<SpotSummary> getRanking(String type, int limit) {
        String safeType = normalize(type);
        int safeLimit = clampLimit(limit);
        if (!enabled) {
            // 禁用态：逐字节等价于原 SpotService.ranking（精确 limit 回源，不碰 Redis）
            return queryDb(safeType, safeLimit);
        }

        String key = KEY_PREFIX + safeType;
        String cached = read(key);
        if (cached != null) {
            try {
                List<SpotSummary> parsed = MAPPER.readValue(cached, new TypeReference<List<SpotSummary>>() { });
                return slice(parsed, safeLimit);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("[ranking-cache] corrupt cache for {} (treat as miss): {}", key, e.getMessage());
            }
        }

        List<SpotSummary> fresh = queryDb(safeType, TOP_N_CACHE);
        write(key, fresh);
        return slice(fresh, safeLimit);
    }

    /** 景点写操作（create/update）后调用：清空全部排行缓存，令下个请求即最新。 */
    public void evictAll() {
        evict("rating");
        evict("popular");
        evict("bookmarks");
    }

    /** 收藏切换后调用：仅清空 bookmarks 排行缓存（其它 type 与收藏数无关）。 */
    public void evictBookmarks() {
        evict("bookmarks");
    }

    private void evict(String type) {
        if (!enabled) {
            return;
        }
        String key = KEY_PREFIX + type;
        try {
            redis.delete(key);
        } catch (DataAccessException e) {
            warnOnce("evict", key, e);
        }
    }

    private String read(String key) {
        if (!enabled) {
            return null;
        }
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            warnOnce("read", key, e);
            return null;
        }
    }

    private void write(String key, List<SpotSummary> items) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key, serialize(items), TTL);
        } catch (DataAccessException e) {
            warnOnce("write", key, e);
        }
    }

    private List<SpotSummary> queryDb(String type, int limit) {
        return spotRepository.ranking(type, PageRequest.of(0, limit)).stream()
                .map(SpotSummary::from)
                .toList();
    }

    private static String serialize(List<SpotSummary> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            // 内存 DTO 序列化不应失败；失败属编程错误，向上抛出由全局异常处理兜底
            throw new IllegalStateException("Failed to serialize ranking cache", e);
        }
    }

    private static String normalize(String type) {
        return type != null && TYPES.contains(type) ? type : "popular";
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), TOP_N_CACHE);
    }

    private static List<SpotSummary> slice(List<SpotSummary> items, int limit) {
        return items.subList(0, Math.min(limit, items.size()));
    }

    private void warnOnce(String op, String key, DataAccessException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs >= WARN_INTERVAL_MS) {
            lastWarnMs = now;
            log.warn("[ranking-cache] redis {} failed for {} (fail-safe to DB): {}", op, key, e.getMessage());
        }
    }
}
