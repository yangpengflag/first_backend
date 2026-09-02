package com.mooc.backend.places.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 城市目的地实体。继承 {@code BaseEntity} 共享主键与审计时间戳。
 *
 * <p>{@code slug} 由 {@code name} 经 {@link CitySlugs#slugify} 自动生成、全表唯一（唯一性兜底）；
 * {@code spots.city_slug} 与路由 {@code /cities/{slug}} 依赖它，作为不透明键使用。
 * {@code description} 为单字段描述（不区分语言，用户/数据源输入什么即存什么）。
 * {@code spotCount} 为聚合查询产物，不冗余存储，由列表/详情组装时实时计算。
 */
@Entity
@Table(name = "cities")
public class City extends BaseEntity {

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "name_zh", nullable = false)
    private String nameZh;

    @Column(name = "cover_image")
    private String coverImage;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "best_season")
    private String bestSeason;

    protected City() {
        // JPA only
    }

    private City(UUID id, String name, String nameZh, String slug, String coverImage,
                 String description, String bestSeason, Instant now) {
        super(id, now);
        this.name = name;
        this.nameZh = nameZh;
        this.slug = slug;
        this.coverImage = coverImage;
        this.description = description;
        this.bestSeason = bestSeason;
    }

    public static City create(UUID id, String name, String nameZh, String slug, String coverImage,
                              String description, String bestSeason, Instant now) {
        return new City(id, name, nameZh, slug, coverImage, description, bestSeason, now);
    }

    public String getSlug() {
        return slug;
    }

    /** 城市英文名（主显）。 */
    public String getName() {
        return name;
    }

    public String getNameZh() {
        return nameZh;
    }

    public String getCoverImage() {
        return coverImage;
    }

    public String getDescription() {
        return description;
    }

    public String getBestSeason() {
        return bestSeason;
    }
}
