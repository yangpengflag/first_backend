package com.mooc.backend.places.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.api.SpotBookmarkStatusResponse;
import com.mooc.backend.places.api.SpotSummary;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotBookmark;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.repository.SpotBookmarkRepository;
import com.mooc.backend.places.repository.SpotRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 景点收藏业务逻辑。
 *
 * <p>切换收藏（一人一景点唯一，取消走物理删除）；状态查询与我的列表均需鉴权。
 * 我的列表按收藏时间倒序返回 {@code SpotSummary}（仅 PUBLISHED，与公开读一致）。
 * 景点不存在统一抛 {@code SPOT_NOT_FOUND}。
 */
@Service
public class SpotBookmarkService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final SpotBookmarkRepository spotBookmarkRepository;
    private final SpotRepository spotRepository;

    public SpotBookmarkService(SpotBookmarkRepository spotBookmarkRepository, SpotRepository spotRepository) {
        this.spotBookmarkRepository = spotBookmarkRepository;
        this.spotRepository = spotRepository;
    }

    @Transactional
    public SpotBookmarkStatusResponse toggle(String spotSlug, UUID userId, Instant now) {
        if (spotRepository.findBySlugAndDeletedFalse(spotSlug).isEmpty()) {
            throw new PlacesException(ErrorCode.SPOT_NOT_FOUND);
        }
        return spotBookmarkRepository.findBySpotSlugAndUserId(spotSlug, userId)
                .map(existing -> {
                    spotBookmarkRepository.delete(existing);
                    return SpotBookmarkStatusResponse.from(spotSlug, false);
                })
                .orElseGet(() -> {
                    SpotBookmark bookmark = SpotBookmark.create(spotSlug, userId, now);
                    spotBookmarkRepository.save(bookmark);
                    return SpotBookmarkStatusResponse.from(spotSlug, true);
                });
    }

    public boolean isBookmarked(String spotSlug, UUID userId) {
        if (spotRepository.findBySlugAndDeletedFalse(spotSlug).isEmpty()) {
            throw new PlacesException(ErrorCode.SPOT_NOT_FOUND);
        }
        return spotBookmarkRepository.findBySpotSlugAndUserId(spotSlug, userId).isPresent();
    }

    public Page<SpotSummary> listSpotBookmarks(UUID userId, int page, int size) {
        int safeSize = clampSize(size);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SpotBookmark> bmPage = spotBookmarkRepository.findPublishedByUserId(userId, pageable);
        Map<String, Spot> bySlug = spotRepository.findBySlugInAndDeletedFalse(
                        bmPage.getContent().stream().map(SpotBookmark::getSpotSlug).toList())
                .stream()
                .collect(Collectors.toMap(Spot::getSlug, s -> s, (a, b) -> a));
        List<SpotSummary> items = bmPage.getContent().stream()
                .map(bm -> bySlug.get(bm.getSpotSlug()))
                .filter(Objects::nonNull)
                .map(SpotSummary::from)
                .toList();
        return new PageImpl<>(items, pageable, bmPage.getTotalElements());
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
