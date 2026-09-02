package com.mooc.backend.places.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 景点收藏实体（维度从 Bookmark 的 post 改为 spotSlug）。
 *
 * <p>一人一景点仅一行：唯一约束 {@code uk_spot_bookmarks_spot_user (spot_slug, user_id)}。
 * 取消收藏走物理删除（释放唯一约束槽位，与 Bookmark 同策略）。
 */
@Entity
@Table(
        name = "spot_bookmarks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_spot_bookmarks_spot_user",
                columnNames = {"spot_slug", "user_id"}))
public class SpotBookmark extends BaseEntity {

    @Column(name = "spot_slug", nullable = false, length = 255)
    private String spotSlug;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    protected SpotBookmark() {
        // JPA only
    }

    private SpotBookmark(UUID id, String spotSlug, UUID userId, Instant now) {
        super(id, now);
        this.spotSlug = spotSlug;
        this.userId = userId;
    }

    public static SpotBookmark create(String spotSlug, UUID userId, Instant now) {
        return new SpotBookmark(UUID.randomUUID(), spotSlug, userId, now);
    }

    public String getSpotSlug() {
        return spotSlug;
    }

    public UUID getUserId() {
        return userId;
    }
}
