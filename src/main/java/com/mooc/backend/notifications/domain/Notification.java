package com.mooc.backend.notifications.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 站内通知实体（Task 1.1，继承 {@code BaseEntity} 软删内核）。
 *
 * <p><b>未读语义</b>：未读 = {@code read_at IS NULL}，不设布尔冗余列；已读后
 * {@code read_at} 写入首次已读时刻且不再覆盖（幂等）。撤销互动只物理删除<b>未读</b>行，
 * 已读通知永不撤销，故本表不存在软删行（{@code deleted} 恒为 false）。
 *
 * <p><b>去重语义</b>：同 recipient + actor + type + post 且未读的行至多一条（应用层去重，
 * 不建唯一约束——评论类天然多条）；再次互动命中时仅刷新 {@code last_interacted_at}，
 * {@code created_at} 保持 BaseEntity 审计语义只写一次。
 *
 * <p><b>索引</b>：{@code (recipient_id, last_interacted_at)} 服务列表倒序分页、
 * {@code (recipient_id, read_at)} 服务未读计数（见 design.md）。
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_recipient_interacted",
                columnList = "recipient_id, last_interacted_at"),
        @Index(name = "idx_notifications_recipient_read",
                columnList = "recipient_id, read_at")
})
public class Notification extends BaseEntity {

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    /** 评论类通知（POST_COMMENTED）指向被评论 / 被回复的目标评论；点赞 / 收藏类为 null。 */
    @Column(name = "comment_id")
    private UUID commentId;

    /** 首次已读时刻；null 表示未读。重复标记已读不覆盖。 */
    @Column(name = "read_at")
    private Instant readAt;

    /** 最后互动时刻：去重命中时刷新；列表排序与条目展示时间均取本字段。 */
    @Column(name = "last_interacted_at", nullable = false)
    private Instant lastInteractedAt;

    protected Notification() {
        // JPA only
    }

    private Notification(UUID id, UUID recipientId, UUID actorId, NotificationType type,
                         UUID postId, UUID commentId, Instant now) {
        super(id, now);
        this.recipientId = recipientId;
        this.actorId = actorId;
        this.type = type;
        this.postId = postId;
        this.commentId = commentId;
        this.lastInteractedAt = now;
    }

    /** 创建新通知，主键与时间由调用方注入（与 BaseEntity / Post 约定一致）。 */
    public static Notification create(UUID recipientId, UUID actorId, NotificationType type,
                                      UUID postId, UUID commentId, Instant now) {
        return new Notification(UUID.randomUUID(), recipientId, actorId, type, postId, commentId, now);
    }

    /**
     * 去重命中时刷新最后互动时刻（列表排序与展示时间随之更新）。
     * 不触碰 {@code created_at}；一次业务变更按 {@code BaseEntity} 约定刷新更新时间。
     */
    public void refreshInteraction(Instant now) {
        this.lastInteractedAt = now;
        this.touch(now);
    }

    /**
     * 标记已读。幂等：已读再调用返回 {@code false} 且不覆盖首次 {@code read_at}；
     * 首次置读返回 {@code true}。
     */
    public boolean markRead(Instant now) {
        if (this.readAt != null) {
            return false;
        }
        this.readAt = now;
        this.touch(now);
        return true;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public NotificationType getType() {
        return type;
    }

    public UUID getPostId() {
        return postId;
    }

    public UUID getCommentId() {
        return commentId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getLastInteractedAt() {
        return lastInteractedAt;
    }

    /** 未读判定（{@code read_at IS NULL} 语义的实体侧表达）。 */
    public boolean isRead() {
        return readAt != null;
    }
}
