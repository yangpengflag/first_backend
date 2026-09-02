package com.mooc.backend.places.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.posts.api.PostSummary;

import java.util.List;

/**
 * 景点详情 DTO（扁平，继承 {@code SpotSummary} 复用全部列表字段 + 详情扩展字段）。
 *
 * <p>{@code description_en} / {@code description_zh}：富文本（英主中副，英文缺失时前端以中文兜底）。
 * {@code nearby_spots}：同城、排除自身的周边 POI（列表项形态）。
 * {@code related_posts}：相关攻略，依赖 {@code post-location-tagging}（尚未落地），本期为空列表。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SpotDetail extends SpotSummary {

    @JsonProperty("description_en") private final String descriptionEn;
    @JsonProperty("description_zh") private final String descriptionZh;
    @JsonProperty("related_posts") private final List<PostSummary> relatedPosts;
    @JsonProperty("nearby_spots") private final List<SpotSummary> nearbySpots;

    SpotDetail(String slug, String nameZh, String nameEn, String citySlug, String category, String status,
               List<String> tags, String level, String addressEn, String addressZh, Double lat, Double lng,
               String coverImageUrl, List<String> galleryUrls, String summaryEn, String summaryZh,
               String openingHours, String ticketInfo, String visitDuration, long viewCount,
               long postCount, Double rating, boolean featured, boolean hiddenGem,
               String descriptionEn, String descriptionZh,
               List<PostSummary> relatedPosts, List<SpotSummary> nearbySpots) {
        super(slug, nameZh, nameEn, citySlug, category, status, tags, level, addressEn, addressZh, lat, lng,
                coverImageUrl, galleryUrls, summaryEn, summaryZh, openingHours, ticketInfo,
                visitDuration, viewCount, postCount, rating, featured, hiddenGem);
        this.descriptionEn = descriptionEn;
        this.descriptionZh = descriptionZh;
        this.relatedPosts = relatedPosts;
        this.nearbySpots = nearbySpots;
    }

    public static SpotDetail from(Spot spot, List<SpotSummary> nearby, List<PostSummary> relatedPosts) {
        return new SpotDetail(spot.getSlug(), spot.getNameZh(), spot.getNameEn(), spot.getCitySlug(),
                spot.getCategory() == null ? null : spot.getCategory().name(),
                spot.getStatus() == null ? null : spot.getStatus().name(),
                spot.getTags(),
                spot.getLevel(), spot.getAddressEn(), spot.getAddressZh(), spot.getLat(), spot.getLng(),
                spot.getCoverImageUrl(), spot.getGalleryUrls(), spot.getSummaryEn(), spot.getSummaryZh(),
                spot.getOpeningHours(), spot.getTicketInfo(), spot.getVisitDuration(),
                spot.getViewCount(), 0L, spot.getRating(), spot.isFeatured(), spot.isHiddenGem(),
                spot.getDescriptionEn(), spot.getDescriptionZh(), relatedPosts, nearby);
    }

    public String getDescriptionEn() { return descriptionEn; }
    public String getDescriptionZh() { return descriptionZh; }
    public List<PostSummary> getRelatedPosts() { return relatedPosts; }
    public List<SpotSummary> getNearbySpots() { return nearbySpots; }
}
