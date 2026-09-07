package com.mooc.backend.entity;

import com.mooc.backend.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 对话消息（change: ai-chat-core）。归属某个 {@link ChatSession}，按角色记录
 * user / assistant 内容；消息顺序由 {@code createdAt}（+ id）决定。
 */
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_messages_session_created", columnList = "session_id, created_at")
})
public class ChatMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChatMessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    protected ChatMessage() {
        // JPA only
    }

    private ChatMessage(UUID id, ChatSession session, ChatMessageRole role,
                        String content, Instant now) {
        super(id, now);
        this.session = session;
        this.role = role;
        this.content = content;
    }

    /** 创建消息，主键与时间由调用方注入（BaseEntity 约定）。 */
    public static ChatMessage create(ChatSession session, ChatMessageRole role,
                                     String content, Instant now) {
        return new ChatMessage(UUID.randomUUID(), session, role, content, now);
    }

    public ChatSession getSession() {
        return session;
    }

    public ChatMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
