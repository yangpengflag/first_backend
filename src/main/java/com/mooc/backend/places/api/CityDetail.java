package com.mooc.backend.places.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.places.domain.City;
import com.mooc.backend.posts.api.PostSummary;

import java.util.List;

/**
 * 城市详情 DTO（扁平，继承 {@code CitySummary} 复用全部列表字段 + 详情扩展字段）。
 *
 * <p>{@code top_spots}：该城市下按 view_count 降序的 Top POI（列表项形态）。
 * {@code related_posts}：相关攻略，依赖 {@code post-location-tagging}（尚未落地），本期为空列表。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class CityDetail extends CitySummary {

    @JsonProperty("top_spots") private final List<SpotSummary> topSpots;
    @JsonProperty("related_posts") private final List<PostSummary> relatedPosts;

    CityDetail(String slug, String name, String nameZh, String coverImage,
               String description, String bestSeason, long spotCount,
               List<SpotSummary> topSpots, List<PostSummary> relatedPosts) {
        super(slug, name, nameZh, coverImage, description, bestSeason, spotCount);
        this.topSpots = topSpots;
        this.relatedPosts = relatedPosts;
    }

    public static CityDetail from(City city, long spotCount, List<SpotSummary> topSpots,
                                  List<PostSummary> relatedPosts) {
        return new CityDetail(city.getSlug(), city.getName(), city.getNameZh(), city.getCoverImage(),
                city.getDescription(), city.getBestSeason(), spotCount, topSpots, relatedPosts);
    }

    public List<SpotSummary> getTopSpots() { return topSpots; }
    public List<PostSummary> getRelatedPosts() { return relatedPosts; }
}
