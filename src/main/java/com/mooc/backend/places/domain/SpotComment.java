package com.mooc.backend.places.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 景点评论实体（镜像 comments.Comment，维度从 postId 改为 spotSlug）。
 *
 * <p>两层树形模型：{@code parentCommentId == null} 为顶层评论，否则为其回复。
 * 软删除通过仓储层 {@code findByIdAndDeletedFalse} 等显式过滤实现（不在类上声明
 * {@code @SQLRestriction}），与 {@code posts} / {@code comments} 约定一致。
 */
@Entity
@Table(name = "spot_comments")
public class SpotComment extends BaseEntity {

    @Column(name = "spot_slug", nullable = false, length = 255)
    private String spotSlug;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "parent_comment_id")
    private UUID parentCommentId;

    protected SpotComment() {
        // JPA only
    }

    private SpotComment(UUID id, String spotSlug, UUID userId, String content, UUID parentCommentId, Instant now) {
        super(id, now);
        this.spotSlug = spotSlug;
        this.userId = userId;
        this.content = content;
        this.parentCommentId = parentCommentId;
    }

    public static SpotComment create(String spotSlug, UUID userId, String content, UUID parentCommentId, Instant now) {
        return new SpotComment(UUID.randomUUID(), spotSlug, userId, content, parentCommentId, now);
    }

    /** 是否为顶层评论（无父）。 */
    public boolean isTopLevel() {
        return parentCommentId == null;
    }

    /** 软删除：置 {@code deleted} 标志并刷新更新时间。 */
    public void softDelete(Instant now) {
        this.markDeleted();
        this.touch(now);
    }

    public String getSpotSlug() {
        return spotSlug;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public UUID getParentCommentId() {
        return parentCommentId;
    }
}
