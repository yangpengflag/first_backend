package com.mooc.backend.places.service;

import com.mooc.backend.places.api.CreateSpotRequest;
import com.mooc.backend.places.api.UpdateSpotRequest;
import com.mooc.backend.places.domain.City;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotBookmark;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotBookmarkRepository;
import com.mooc.backend.places.repository.SpotRepository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排行榜缓存写失效接线测试（change: add-spot-ranking-redis-cache）。
 *
 * <p>断言：Spot 写操作（create/update）提交后触发 {@code evictAll()}；收藏切换（add / remove）
 * 提交后触发 {@code evictBookmarks()}——保证 spec「收藏切换后下个请求即最新」与写失效语义落地。
 */
class RankingCacheEvictionWiringTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void createEvictsAllRankingKeys() {
        SpotRepository repo = mock(SpotRepository.class);
        CityRepository cityRepo = mock(CityRepository.class);
        RankingCacheService cache = mock(RankingCacheService.class);
        SpotService svc = new SpotService(repo, cityRepo, cache);

        when(cityRepo.findBySlugAndDeletedFalse("hangzhou")).thenReturn(Optional.of(mock(City.class)));
        when(repo.findBySlug("hangzhou-west-lake")).thenReturn(Optional.empty());
        when(repo.save(any(Spot.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.create(new CreateSpotRequest("West Lake", "西湖", null, null, null, null, null, null, null,
                        "hangzhou", SpotCategory.NATURE, null, null, null, null, null, null, null, null, null,
                        4.7, false, false),
                NOW);

        verify(cache).evictAll();
    }

    @Test
    void updateEvictsAllRankingKeys() {
        SpotRepository repo = mock(SpotRepository.class);
        CityRepository cityRepo = mock(CityRepository.class);
        RankingCacheService cache = mock(RankingCacheService.class);
        SpotService svc = new SpotService(repo, cityRepo, cache);

        Spot spot = spot("hangzhou-west-lake");
        when(repo.findBySlugAndDeletedFalse("hangzhou-west-lake")).thenReturn(Optional.of(spot));
        when(repo.save(any(Spot.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.update("hangzhou-west-lake",
                new UpdateSpotRequest("New Lake", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, 4.9, true, null),
                NOW);

        verify(cache).evictAll();
    }

    @Test
    void toggleAddEvictsBookmarksKey() {
        SpotBookmarkRepository bmRepo = mock(SpotBookmarkRepository.class);
        SpotRepository repo = mock(SpotRepository.class);
        RankingCacheService cache = mock(RankingCacheService.class);
        SpotBookmarkService svc = new SpotBookmarkService(bmRepo, repo, cache);

        when(repo.findBySlugAndDeletedFalse("hz-a")).thenReturn(Optional.of(mock(Spot.class)));
        when(bmRepo.findBySpotSlugAndUserId(eq("hz-a"), eq(USER))).thenReturn(Optional.empty());
        when(bmRepo.save(any(SpotBookmark.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = svc.toggle("hz-a", USER, NOW);

        verify(cache).evictBookmarks();
        org.assertj.core.api.Assertions.assertThat(resp.isBookmarked()).isTrue();
    }

    @Test
    void toggleRemoveEvictsBookmarksKey() {
        SpotBookmarkRepository bmRepo = mock(SpotBookmarkRepository.class);
        SpotRepository repo = mock(SpotRepository.class);
        RankingCacheService cache = mock(RankingCacheService.class);
        SpotBookmarkService svc = new SpotBookmarkService(bmRepo, repo, cache);

        when(repo.findBySlugAndDeletedFalse("hz-a")).thenReturn(Optional.of(mock(Spot.class)));
        when(bmRepo.findBySpotSlugAndUserId(eq("hz-a"), eq(USER)))
                .thenReturn(Optional.of(mock(SpotBookmark.class)));

        var resp = svc.toggle("hz-a", USER, NOW);

        verify(cache).evictBookmarks();
        org.assertj.core.api.Assertions.assertThat(resp.isBookmarked()).isFalse();
    }

    private static Spot spot(String slug) {
        return Spot.create(UUID.randomUUID(), slug, "West Lake", "West Lake", "hangzhou", SpotCategory.NATURE,
                List.of("lake"), null, "addr en", "addr zh", 30.25, 120.15, "https://img.example.com/c.jpg",
                List.of(), "sum en", "sum zh", "desc en", "desc zh", "09:00-17:00", "free", "2h",
                4.5, false, false, SpotStatus.PUBLISHED, NOW);
    }
}
