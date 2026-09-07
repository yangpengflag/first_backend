package com.mooc.backend.ai.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 对话会话（change: ai-chat-core）。
 *
 * <p>以客户端生成的 {@code sessionId}（UUID 字符串）为逻辑键（unique），跨账号解耦：
 * 游客会话 {@code ownerUserId} 为 null（预留登录增强，本期恒 null）。会话按创建时间
 * 保留 N 天，超期由清理任务删除（见 AiChatProperties.retentionDays）。
 */
@Entity
@Table(name = "chat_sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_chat_sessions_session_id", columnNames = "session_id")
})
public class ChatSession extends BaseEntity {

    @Column(name = "session_id", nullable = false, length = 36, updatable = false)
    private String sessionId;

    /** 会话属主（预留登录增强）：游客会话为 null，本期不写入。 */
    @Column(name = "owner_user_id", updatable = false)
    private UUID ownerUserId;

    protected ChatSession() {
        // JPA only
    }

    private ChatSession(UUID id, String sessionId, UUID ownerUserId, Instant now) {
        super(id, now);
        this.sessionId = sessionId;
        this.ownerUserId = ownerUserId;
    }

    /** 创建会话，主键与时间由调用方注入（BaseEntity 约定）。 */
    public static ChatSession create(String sessionId, UUID ownerUserId, Instant now) {
        return new ChatSession(UUID.randomUUID(), sessionId, ownerUserId, now);
    }

    public String getSessionId() {
        return sessionId;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }
}
