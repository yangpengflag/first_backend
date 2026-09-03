package com.mooc.backend.places.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotStatus;

import java.util.List;

/**
 * 景点列表项 / 嵌套项（周边 POI、城市 Top POI）出网白名单 DTO（snake_case）。
 *
 * <p>{@code postCount} 依赖 {@code post-location-tagging}（尚未落地），本期置 0。
 * {@code rating} 可空（AI 爬虫估算，缺省 UI 不渲染评分区块）。{@code category} / {@code status}
 * 序列化枚举名（小写英文）。{@code status} 标识发布状态，公开读仅返回 PUBLISHED。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SpotSummary extends BaseResponse {

    @JsonProperty("slug") private final String slug;
    @JsonProperty("name_zh") private final String nameZh;
    @JsonProperty("name_en") private final String nameEn;
    @JsonProperty("city_slug") private final String citySlug;
    @JsonProperty("category") private final String category;
    @JsonProperty("status") private final String status;
    @JsonProperty("tags") private final List<String> tags;
    @JsonProperty("level") private final String level;
    @JsonProperty("address_en") private final String addressEn;
    @JsonProperty("address_zh") private final String addressZh;
    @JsonProperty("lat") private final Double lat;
    @JsonProperty("lng") private final Double lng;
    @JsonProperty("cover_image_url") private final String coverImageUrl;
    @JsonProperty("gallery_urls") private final List<String> galleryUrls;
    @JsonProperty("summary_en") private final String summaryEn;
    @JsonProperty("summary_zh") private final String summaryZh;
    @JsonProperty("opening_hours") private final String openingHours;
    @JsonProperty("ticket_info") private final String ticketInfo;
    @JsonProperty("visit_duration") private final String visitDuration;
    @JsonProperty("view_count") private final long viewCount;
    @JsonProperty("post_count") private final long postCount;
    @JsonProperty("rating") private final Double rating;
    @JsonProperty("featured") private final boolean featured;
    @JsonProperty("hidden_gem") private final boolean hiddenGem;

    /**
     * 唯一构造器兼作 Jackson 反序列化入口（Redis 缓存回读，change: add-spot-ranking-redis-cache）。
     *
     * <p>出网序列化仍走字段 {@code @JsonProperty}（snake_case）+ getter，此构造器注解只补充回读能力，
     * 不影响 HTTP 输出。{@code request_id} 非构造器入参：反序列化时经 {@code super()}（BaseResponse）
     * 按当前线程 MDC 重建，保证缓存命中的关联 ID 语义与回源一致。
     */
    @JsonCreator
    SpotSummary(@JsonProperty("slug") String slug, @JsonProperty("name_zh") String nameZh,
                @JsonProperty("name_en") String nameEn, @JsonProperty("city_slug") String citySlug,
                @JsonProperty("category") String category, @JsonProperty("status") String status,
                @JsonProperty("tags") List<String> tags, @JsonProperty("level") String level,
                @JsonProperty("address_en") String addressEn, @JsonProperty("address_zh") String addressZh,
                @JsonProperty("lat") Double lat, @JsonProperty("lng") Double lng,
                @JsonProperty("cover_image_url") String coverImageUrl,
                @JsonProperty("gallery_urls") List<String> galleryUrls,
                @JsonProperty("summary_en") String summaryEn, @JsonProperty("summary_zh") String summaryZh,
                @JsonProperty("opening_hours") String openingHours, @JsonProperty("ticket_info") String ticketInfo,
                @JsonProperty("visit_duration") String visitDuration, @JsonProperty("view_count") long viewCount,
                @JsonProperty("post_count") long postCount, @JsonProperty("rating") Double rating,
                @JsonProperty("featured") boolean featured, @JsonProperty("hidden_gem") boolean hiddenGem) {
        super();
        this.slug = slug;
        this.nameZh = nameZh;
        this.nameEn = nameEn;
        this.citySlug = citySlug;
        this.category = category;
        this.status = status;
        this.tags = tags;
        this.level = level;
        this.addressEn = addressEn;
        this.addressZh = addressZh;
        this.lat = lat;
        this.lng = lng;
        this.coverImageUrl = coverImageUrl;
        this.galleryUrls = galleryUrls;
        this.summaryEn = summaryEn;
        this.summaryZh = summaryZh;
        this.openingHours = openingHours;
        this.ticketInfo = ticketInfo;
        this.visitDuration = visitDuration;
        this.viewCount = viewCount;
        this.postCount = postCount;
        this.rating = rating;
        this.featured = featured;
        this.hiddenGem = hiddenGem;
    }

    public static SpotSummary from(Spot spot) {
        return new SpotSummary(spot.getSlug(), spot.getNameZh(), spot.getNameEn(), spot.getCitySlug(),
                spot.getCategory() == null ? null : spot.getCategory().name(),
                spot.getStatus() == null ? null : spot.getStatus().name(),
                spot.getTags(),
                spot.getLevel(), spot.getAddressEn(), spot.getAddressZh(), spot.getLat(), spot.getLng(),
                spot.getCoverImageUrl(), spot.getGalleryUrls(), spot.getSummaryEn(), spot.getSummaryZh(),
                spot.getOpeningHours(), spot.getTicketInfo(), spot.getVisitDuration(),
                spot.getViewCount(), 0L, spot.getRating(), spot.isFeatured(), spot.isHiddenGem());
    }

    public String getSlug() { return slug; }
    public String getNameZh() { return nameZh; }
    public String getNameEn() { return nameEn; }
    public String getCitySlug() { return citySlug; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public List<String> getTags() { return tags; }
    public String getLevel() { return level; }
    public String getAddressEn() { return addressEn; }
    public String getAddressZh() { return addressZh; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public String getCoverImageUrl() { return coverImageUrl; }
    public List<String> getGalleryUrls() { return galleryUrls; }
    public String getSummaryEn() { return summaryEn; }
    public String getSummaryZh() { return summaryZh; }
    public String getOpeningHours() { return openingHours; }
    public String getTicketInfo() { return ticketInfo; }
    public String getVisitDuration() { return visitDuration; }
    public long getViewCount() { return viewCount; }
    public long getPostCount() { return postCount; }
    public Double getRating() { return rating; }
    public boolean isFeatured() { return featured; }
    public boolean isHiddenGem() { return hiddenGem; }
}
