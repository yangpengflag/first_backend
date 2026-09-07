package com.mooc.backend.repository;

import com.mooc.backend.entity.ChatMessage;
import com.mooc.backend.entity.ChatSession;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * AI 消息仓储（change: ai-chat-core）。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /**
     * 某会话最近 n 条消息（新→旧），由 service 反转成语义顺序。排序依赖 BaseEntity
     * 的 createdAt，同刻消息按主键倒序兜底（Hibernate 生成 UUID 单调性弱，仅兜底展示）。
     */
    List<ChatMessage> findBySession_IdAndDeletedFalseOrderByCreatedAtDescIdDesc(
            UUID sessionId, Pageable pageable);

    /** 清理：删除某会话的全部消息（软删行一并物理删，清理不走软删语义）。 */
    void deleteBySession_Id(UUID sessionId);
}
