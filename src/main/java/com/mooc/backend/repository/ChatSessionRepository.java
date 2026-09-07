package com.mooc.backend.repository;

import com.mooc.backend.entity.ChatSession;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 会话仓储（change: ai-chat-core）。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    /** 按客户端生成的逻辑键查会话。 */
    Optional<ChatSession> findBySessionId(String sessionId);

    /** 供清理任务使用：保留期前的会话 id 列表（service 内级联删消息）。 */
    List<ChatSession> findByCreatedAtBefore(Instant cutoff);
}
