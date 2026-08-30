package com.mooc.backend.bookmarks.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 收藏实体（继承 {@code BaseEntity} 软删内核）。
 *
 * <p>一人一帖仅一行收藏：唯一约束 {@code uk_bookmarks_post_user (post_id, user_id)}。
 * 取消收藏走物理删除（与 votes 同理由，释放唯一约束槽位）。
 */
@Entity
@Table(
        name = "bookmarks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bookmarks_post_user",
                columnNames = {"post_id", "user_id"}))
public class Bookmark extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    protected Bookmark() {
        // JPA only
    }

    private Bookmark(UUID id, UUID postId, UUID userId, Instant now) {
        super(id, now);
        this.postId = postId;
        this.userId = userId;
    }

    public static Bookmark create(UUID postId, UUID userId, Instant now) {
        return new Bookmark(UUID.randomUUID(), postId, userId, now);
    }

    public UUID getPostId() {
        return postId;
    }

    public UUID getUserId() {
        return userId;
    }
}
