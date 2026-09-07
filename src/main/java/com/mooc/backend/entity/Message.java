package com.mooc.backend.entity;

import com.mooc.backend.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 私信消息实体（Task 1.1，继承 {@code BaseEntity} 软删内核）。
 *
 * <p><b>内容</b>：纯文本，业务上限 2000 字符（入站 trim + 校验由 {@code MessagingService} 保证），
 * 列类型 TEXT，HTML/标签按字面文本处理（出站转义由前端负责）。
 *
 * <p><b>已读语义</b>：{@code read_at} 为接收方首次阅读时刻；<b>发送者自己的消息恒为 null</b>
 * （写路径只批量置读「对方发出」的消息），也不参与任何未读统计。
 * MVP 无 "Seen" 回执 UI，{@code read_at} 仅驱动 badge 数字。
 *
 * <p><b>索引</b>：{@code idx_messages_conversation_created (conversation_id, created_at)}
 * 服务历史倒查分页（design.md）。软删字段仅对齐 {@code BaseEntity}，本期无删除入口。
 */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_conversation_created", columnList = "conversation_id, created_at")
})
public class Message extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 接收方首次已读时刻；null 表示未读。发送者自己的消息恒为 null。 */
    @Column(name = "read_at")
    private Instant readAt;

    protected Message() {
        // JPA only
    }

    private Message(UUID id, UUID conversationId, UUID senderId, String content, Instant now) {
        super(id, now);
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
    }

    /** 创建消息：主键与时间由调用方注入（与 BaseEntity / Notification 约定一致）；read_at 初始为 null。 */
    public static Message create(UUID conversationId, UUID senderId, String content, Instant now) {
        return new Message(UUID.randomUUID(), conversationId, senderId, content, now);
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public String getContent() {
        return content;
    }

    public Instant getReadAt() {
        return readAt;
    }

    /** 未读判定（{@code read_at IS NULL} 语义的实体侧表达）。 */
    public boolean isRead() {
        return readAt != null;
    }
}
