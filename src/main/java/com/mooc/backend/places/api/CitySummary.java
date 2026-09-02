package com.mooc.backend.places.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;
import com.mooc.backend.places.domain.City;

/**
 * 城市列表项出网白名单 DTO（snake_case，继承 {@code BaseResponse} 自带 request_id）。
 *
 * <p>{@code spot_count} 由列表组装时按 city_slug 实时聚合。城市不再有省份 / 图集 / 双语摘要 /
 * 坐标 / 浏览量 / 精选标记等字段（见 {@code city-module} change 精简后的 City 模型）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class CitySummary extends BaseResponse {

    @JsonProperty("slug") private final String slug;
    @JsonProperty("name") private final String name;
    @JsonProperty("name_zh") private final String nameZh;
    @JsonProperty("cover_image") private final String coverImage;
    @JsonProperty("description") private final String description;
    @JsonProperty("best_season") private final String bestSeason;
    @JsonProperty("spot_count") private final long spotCount;

    CitySummary(String slug, String name, String nameZh, String coverImage,
                String description, String bestSeason, long spotCount) {
        super();
        this.slug = slug;
        this.name = name;
        this.nameZh = nameZh;
        this.coverImage = coverImage;
        this.description = description;
        this.bestSeason = bestSeason;
        this.spotCount = spotCount;
    }

    public static CitySummary from(City city, long spotCount) {
        return new CitySummary(city.getSlug(), city.getName(), city.getNameZh(), city.getCoverImage(),
                city.getDescription(), city.getBestSeason(), spotCount);
    }

    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getNameZh() { return nameZh; }
    public String getCoverImage() { return coverImage; }
    public String getDescription() { return description; }
    public String getBestSeason() { return bestSeason; }
    public long getSpotCount() { return spotCount; }
}
