package com.mooc.backend.places.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.api.SpotDetail;
import com.mooc.backend.places.api.SpotListResponse;
import com.mooc.backend.places.api.SpotSummary;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.repository.SpotRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 景点读服务（只读）。列表做城市 / 分类 / 标签 / 关键词筛选、排序、分页；详情组装周边 POI 与相关攻略占位。
 *
 * <p>{@code postCount} / 相关攻略依赖 {@code post-location-tagging}（尚未落地），本期置 0 / 空列表。
 * 详情访问触发 {@code ViewCountService} 异步计数（防刷）。
 */
@Service
public class SpotService {

    private final SpotRepository spotRepository;

    public SpotService(SpotRepository spotRepository) {
        this.spotRepository = spotRepository;
    }

    public SpotListResponse list(String city, String category, String tag, String q, String sort,
                                int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page - 1), size);
        List<Spot> spots = spotRepository.search(city, category, tag, q, sort, pageable);
        long total = spotRepository.countSearch(city, category, tag, q);
        List<SpotSummary> items = spots.stream().map(SpotSummary::from).toList();
        return SpotListResponse.of(items, page, size, total);
    }

    public SpotDetail getBySlug(String slug) {
        Spot spot = spotRepository.findBySlugAndDeletedFalse(slug)
                .orElseThrow(() -> new PlacesException(ErrorCode.SPOT_NOT_FOUND));
        List<SpotSummary> nearby = spotRepository
                .findByCitySlugAndDeletedFalseAndIdNot(spot.getCitySlug(), spot.getId(),
                        PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount")))
                .stream()
                .map(SpotSummary::from)
                .toList();
        // 相关攻略：post-location-tagging 落地前为空
        return SpotDetail.from(spot, nearby, List.of());
    }
}
