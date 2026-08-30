package com.mooc.backend.comments.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 评论实体（继承 {@code BaseEntity} 软删内核）。
 *
 * <p>两层树形模型：{@code parentCommentId == null} 为顶层评论，否则为其回复。
 * 软删除通过仓储层 {@code findByIdAndDeletedFalse} 等显式过滤实现（不在类上声明
 * {@code @SQLRestriction}），与 {@code posts} 约定一致。
 */
@Entity
@Table(name = "comments")
public class Comment extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "parent_comment_id")
    private UUID parentCommentId;

    protected Comment() {
        // JPA only
    }

    private Comment(UUID id, UUID postId, UUID userId, String content, UUID parentCommentId, Instant now) {
        super(id, now);
        this.postId = postId;
        this.userId = userId;
        this.content = content;
        this.parentCommentId = parentCommentId;
    }

    public static Comment create(UUID postId, UUID userId, String content, UUID parentCommentId, Instant now) {
        return new Comment(UUID.randomUUID(), postId, userId, content, parentCommentId, now);
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

    public UUID getPostId() {
        return postId;
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
