package com.mooc.backend.places.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 景点 POI 实体。继承 {@code BaseEntity}。
 *
 * <p>{@code slug} 复合 {@code {citySlug}-{spotSlug}} 且全局唯一，仅作不透明路由键（系统不对其分割解析）。
 * {@code citySlug} 非空（归属 + 消歧），写入由 service 层校验城市存在（见 {@code SpotService}）。
 * {@code category} 以英文枚举持久化；{@code tags} / {@code galleryUrls} 为 JSON 数组。
 * {@code rating} 可空（AI 爬虫估算，缺省 UI 不渲染）；{@code postCount} 由聚合查询产出、不冗余存储。
 */
@Entity
@Table(name = "spots")
public class Spot extends BaseEntity {

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "name_zh", nullable = false)
    private String nameZh;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "city_slug", nullable = false)
    private String citySlug;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private SpotCategory category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false)
    private List<String> tags = new ArrayList<>();

    @Column(name = "level")
    private String level;

    @Column(name = "address_en", columnDefinition = "TEXT")
    private String addressEn;

    @Column(name = "address_zh", columnDefinition = "TEXT")
    private String addressZh;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lng")
    private Double lng;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gallery_urls", nullable = false)
    private List<String> galleryUrls = new ArrayList<>();

    @Column(name = "summary_en", columnDefinition = "TEXT")
    private String summaryEn;

    @Column(name = "summary_zh", columnDefinition = "TEXT")
    private String summaryZh;

    @Column(name = "description_en", columnDefinition = "TEXT")
    private String descriptionEn;

    @Column(name = "description_zh", columnDefinition = "TEXT")
    private String descriptionZh;

    @Column(name = "opening_hours", columnDefinition = "TEXT")
    private String openingHours;

    @Column(name = "ticket_info", columnDefinition = "TEXT")
    private String ticketInfo;

    @Column(name = "visit_duration")
    private String visitDuration;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "rating")
    private Double rating;

    @Column(name = "featured", nullable = false)
    private boolean featured = false;

    @Column(name = "hidden_gem", nullable = false)
    private boolean hiddenGem = false;

    protected Spot() {
        // JPA only
    }

    private Spot(UUID id, String slug, String nameZh, String nameEn, String citySlug, SpotCategory category,
                List<String> tags, String level, String addressEn, String addressZh, Double lat, Double lng,
                String coverImageUrl, List<String> galleryUrls, String summaryEn, String summaryZh,
                String descriptionEn, String descriptionZh, String openingHours, String ticketInfo,
                String visitDuration, Double rating, boolean featured, boolean hiddenGem, Instant now) {
        super(id, now);
        this.slug = slug;
        this.nameZh = nameZh;
        this.nameEn = nameEn;
        this.citySlug = citySlug;
        this.category = category;
        this.tags = new ArrayList<>(tags);
        this.level = level;
        this.addressEn = addressEn;
        this.addressZh = addressZh;
        this.lat = lat;
        this.lng = lng;
        this.coverImageUrl = coverImageUrl;
        this.galleryUrls = new ArrayList<>(galleryUrls);
        this.summaryEn = summaryEn;
        this.summaryZh = summaryZh;
        this.descriptionEn = descriptionEn;
        this.descriptionZh = descriptionZh;
        this.openingHours = openingHours;
        this.ticketInfo = ticketInfo;
        this.visitDuration = visitDuration;
        this.rating = rating;
        this.featured = featured;
        this.hiddenGem = hiddenGem;
    }

    public static Spot create(UUID id, String slug, String nameZh, String nameEn, String citySlug, SpotCategory category,
                              List<String> tags, String level, String addressEn, String addressZh, Double lat, Double lng,
                              String coverImageUrl, List<String> galleryUrls, String summaryEn, String summaryZh,
                              String descriptionEn, String descriptionZh, String openingHours, String ticketInfo,
                              String visitDuration, Double rating, boolean featured, boolean hiddenGem, Instant now) {
        return new Spot(id, slug, nameZh, nameEn, citySlug, category, tags, level, addressEn, addressZh, lat, lng,
                coverImageUrl, galleryUrls, summaryEn, summaryZh, descriptionEn, descriptionZh,
                openingHours, ticketInfo, visitDuration, rating, featured, hiddenGem, now);
    }

    /** 详情访问计数 +1（异步防刷在 {@code ViewCountService} 中调度，此处仅改内存态）。 */
    public void incrementViewCount() {
        this.viewCount++;
    }

    public String getSlug() {
        return slug;
    }

    public String getNameZh() {
        return nameZh;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getCitySlug() {
        return citySlug;
    }

    public SpotCategory getCategory() {
        return category;
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public String getLevel() {
        return level;
    }

    public String getAddressEn() {
        return addressEn;
    }

    public String getAddressZh() {
        return addressZh;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLng() {
        return lng;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public List<String> getGalleryUrls() {
        return List.copyOf(galleryUrls);
    }

    public String getSummaryEn() {
        return summaryEn;
    }

    public String getSummaryZh() {
        return summaryZh;
    }

    public String getDescriptionEn() {
        return descriptionEn;
    }

    public String getDescriptionZh() {
        return descriptionZh;
    }

    public String getOpeningHours() {
        return openingHours;
    }

    public String getTicketInfo() {
        return ticketInfo;
    }

    public String getVisitDuration() {
        return visitDuration;
    }

    public long getViewCount() {
        return viewCount;
    }

    public Double getRating() {
        return rating;
    }

    public boolean isFeatured() {
        return featured;
    }

    public boolean isHiddenGem() {
        return hiddenGem;
    }
}
