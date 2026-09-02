package com.mooc.backend.posts.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 帖子 ↔ 景点多对多关联表实体（替代原 {@code Post.spot_ids} JSON 列）。
 *
 * <p>唯一约束 {@code uk_post_spots_post_spot (post_id, spot_slug)} 保证一帖对同一景点仅一行；
 * 取消关联走物理删除（释放唯一约束槽位，与 {@code Bookmark} 同策略）。
 * 景点以 slug 为键，便于按 {@code spot_slug} 反查关联帖子（详见 Post 模块查询）。
 */
@Entity
@Table(
        name = "post_spots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_spots_post_spot",
                columnNames = {"post_id", "spot_slug"}),
        indexes = @Index(name = "idx_post_spots_spot", columnList = "spot_slug"))
public class PostSpot extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "spot_slug", nullable = false, length = 255)
    private String spotSlug;

    protected PostSpot() {
        // JPA only
    }

    private PostSpot(UUID id, UUID postId, String spotSlug, Instant now) {
        super(id, now);
        this.postId = postId;
        this.spotSlug = spotSlug;
    }

    public static PostSpot create(UUID postId, String spotSlug, Instant now) {
        return new PostSpot(UUID.randomUUID(), postId, spotSlug, now);
    }

    public UUID getPostId() {
        return postId;
    }

    public String getSpotSlug() {
        return spotSlug;
    }
}
