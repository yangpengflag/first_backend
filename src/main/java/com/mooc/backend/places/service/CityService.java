package com.mooc.backend.places.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.api.CityDetail;
import com.mooc.backend.places.api.CityListResponse;
import com.mooc.backend.places.api.CitySummary;
import com.mooc.backend.places.api.SpotSummary;
import com.mooc.backend.places.domain.City;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 城市读服务（只读）。列表按 {@code name} 升序分页，详情组装 Top POI 与相关攻略占位。
 *
 * <p>{@code postCount} / 相关攻略依赖 {@code post-location-tagging}（尚未落地），本期置 0 / 空列表。
 * 所有对外只读，不写实体。
 */
@Service
public class CityService {

    private final CityRepository cityRepository;
    private final SpotRepository spotRepository;

    public CityService(CityRepository cityRepository, SpotRepository spotRepository) {
        this.cityRepository = cityRepository;
        this.spotRepository = spotRepository;
    }

    public CityListResponse list(int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page - 1), size, Sort.by(Sort.Direction.ASC, "name"));
        Page<City> cityPage = cityRepository.findByDeletedFalse(pageable);
        List<CitySummary> items = cityPage.getContent().stream().map(this::toSummary).toList();
        return CityListResponse.of(items, page, size, cityPage.getTotalElements());
    }

    public CityDetail getBySlug(String slug) {
        City city = cityRepository.findBySlugAndDeletedFalse(slug)
                .orElseThrow(() -> new PlacesException(ErrorCode.CITY_NOT_FOUND));
        List<SpotSummary> topSpots = spotRepository
                .findByCitySlugAndDeletedFalse(slug,
                        PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount")))
                .stream()
                .map(SpotSummary::from)
                .toList();
        // 相关攻略：post-location-tagging 落地前为空
        long spotCount = spotRepository.countByCitySlugAndDeletedFalse(city.getSlug());
        return CityDetail.from(city, spotCount, topSpots, List.of());
    }

    private CitySummary toSummary(City city) {
        long spotCount = spotRepository.countByCitySlugAndDeletedFalse(city.getSlug());
        return CitySummary.from(city, spotCount);
    }
}
