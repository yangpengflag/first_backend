package com.mooc.backend.places.service;

import com.mooc.backend.places.api.SpotSummary;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;
import com.mooc.backend.places.repository.SpotRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 景点排行榜缓存服务单元测试（change: add-spot-ranking-redis-cache）。
 *
 * <p>覆盖：命中不触库 / 未命中回源并把 Top50 写入（TTL=300s）/ 命中按 limit 截断（含 clamp 与
 * 缓存条数不足）/ 非法 type 归一化共用 key / 禁用态精确 limit 回源且不碰 Redis / Redis 读写异常
 * fail-safe 直查 / evictAll 与 evictBookmarks 的 key 范围。全程 Mockito，不依赖真 Redis。
 */
class RankingCacheServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void cacheHitReturnsSlicedWithoutQueryingDb() throws Exception {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        List<SpotSummary> cached = List.of(summary("hz-a", "A"), summary("hz-b", "B"), summary("hz-c", "C"));
        when(redis.opsForValue()).thenReturn(vo);
        when(vo.get("spot:ranking:popular")).thenReturn(SpotSummaryCacheJson.mapper().writeValueAsString(cached));

        List<SpotSummary> out = svc.getRanking("popular", 2);

        assertThat(out).extracting(SpotSummary::getSlug).containsExactly("hz-a", "hz-b");
        verify(repo, never()).ranking(anyString(), any(Pageable.class));
    }

    @Test
    void cacheMissQueriesDbStoresTop50WithTtlAndSlices() throws Exception {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        when(redis.opsForValue()).thenReturn(vo);
        when(vo.get("spot:ranking:popular")).thenReturn(null);
        List<Spot> db = List.of(spot("hz-a", "A"), spot("hz-b", "B"), spot("hz-c", "C"), spot("hz-d", "D"));
        when(repo.ranking(eq("popular"), eq(PageRequest.of(0, 50)))).thenReturn(db);

        List<SpotSummary> out = svc.getRanking("popular", 3);

        assertThat(out).extracting(SpotSummary::getSlug).containsExactly("hz-a", "hz-b", "hz-c");
        verify(vo).set(eq("spot:ranking:popular"), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void cacheHitReturnsEverythingWhenStoredSmallerThanLimitOrLimitClamped() throws Exception {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        List<SpotSummary> cached = List.of(summary("hz-a", "A"), summary("hz-b", "B"), summary("hz-c", "C"));
        when(redis.opsForValue()).thenReturn(vo);
        when(vo.get("spot:ranking:popular")).thenReturn(SpotSummaryCacheJson.mapper().writeValueAsString(cached));

        assertThat(svc.getRanking("popular", 200)).hasSize(3); // clamp 到 50，但缓存只有 3 条
        assertThat(svc.getRanking("popular", 50)).hasSize(3);
        verify(repo, never()).ranking(anyString(), any(Pageable.class));
    }

    @Test
    void unknownTypeAndNullSharePopularKey() throws Exception {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        List<SpotSummary> cached = List.of(summary("hz-a", "A"));
        String json = SpotSummaryCacheJson.mapper().writeValueAsString(cached);
        when(redis.opsForValue()).thenReturn(vo);
        when(vo.get("spot:ranking:popular")).thenReturn(json);

        assertThat(svc.getRanking("bogus", 10)).hasSize(1);
        assertThat(svc.getRanking(null, 10)).hasSize(1);
        verify(vo, org.mockito.Mockito.times(2)).get("spot:ranking:popular");
        verify(repo, never()).ranking(anyString(), any(Pageable.class));
    }

    @Test
    void disabledModeQueriesDbWithExactLimitAndNeverTouchesRedis() {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, false);

        when(repo.ranking(eq("popular"), eq(PageRequest.of(0, 10))))
                .thenReturn(List.of(spot("hz-a", "A"), spot("hz-b", "B"), spot("hz-c", "C"), spot("hz-d", "D")));

        List<SpotSummary> out = svc.getRanking("popular", 10);

        assertThat(out).hasSize(4);
        verify(repo).ranking(eq("popular"), eq(PageRequest.of(0, 10))); // 精确请求 limit，而非 50
        verifyNoInteractions(redis);
    }

    @Test
    void redisReadFailureStillReturnsDbData() {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        when(redis.opsForValue()).thenReturn(vo);
        when(vo.get("spot:ranking:popular")).thenThrow(new RedisConnectionFailureException("boom"));
        when(repo.ranking(eq("popular"), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of(spot("hz-a", "A"), spot("hz-b", "B"), spot("hz-c", "C"), spot("hz-d", "D")));

        List<SpotSummary> out = svc.getRanking("popular", 50);

        assertThat(out).extracting(SpotSummary::getSlug).containsExactly("hz-a", "hz-b", "hz-c", "hz-d");
        verify(repo).ranking(eq("popular"), eq(PageRequest.of(0, 50)));
    }

    @Test
    void redisWriteFailureStillReturnsFreshDbData() {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        when(redis.opsForValue()).thenReturn(vo);
        when(vo.get("spot:ranking:popular")).thenReturn(null);
        when(repo.ranking(eq("popular"), eq(PageRequest.of(0, 50))))
                .thenReturn(List.of(spot("hz-a", "A"), spot("hz-b", "B")));
        doThrow(new RedisConnectionFailureException("boom"))
                .when(vo).set(eq("spot:ranking:popular"), anyString(), eq(Duration.ofSeconds(300)));

        List<SpotSummary> out = svc.getRanking("popular", 2);

        assertThat(out).hasSize(2);
    }

    @Test
    void evictAllDeletesThreeRankingKeys() {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        svc.evictAll();

        verify(redis).delete("spot:ranking:rating");
        verify(redis).delete("spot:ranking:popular");
        verify(redis).delete("spot:ranking:bookmarks");
    }

    @Test
    void evictBookmarksDeletesOnlyBookmarksKey() {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, true);

        svc.evictBookmarks();

        verify(redis).delete("spot:ranking:bookmarks");
        verify(redis, never()).delete("spot:ranking:rating");
        verify(redis, never()).delete("spot:ranking:popular");
    }

    @Test
    void disabledModeEvictIsNoop() {
        SpotRepository repo = mock(SpotRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RankingCacheService svc = new RankingCacheService(repo, redis, false);

        svc.evictAll();
        svc.evictBookmarks();

        verifyNoInteractions(redis);
    }

    private static Spot spot(String slug, String name) {
        return Spot.create(UUID.randomUUID(), slug, name, name, "hangzhou", SpotCategory.NATURE,
                List.of("lake"), null, "addr en", "addr zh", 30.25, 120.15, "https://img.example.com/c.jpg",
                List.of("g1"), "sum en", "sum zh", "desc en", "desc zh", "09:00-17:00", "free", "2h",
                4.5, true, false, SpotStatus.PUBLISHED, NOW);
    }

    private static SpotSummary summary(String slug, String name) {
        return SpotSummary.from(spot(slug, name));
    }
}
