package com.mooc.backend.messaging.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息仓储（Task 1.1）。
 *
 * <p>历史分页与未读聚合见 {@link MessageRepositoryCustom} 的 native 实现；
 * 自动已读用 bulk JPQL（{@code @Modifying}），并开启
 * {@code clearAutomatically / flushAutomatically} 防止持久化上下文读到过期快照。
 * 已读语义以「对方发出且 read_at IS NULL」为界：发送者自己的消息恒不触碰，
 * 重复调用幂等（已读行不覆盖首次 read_at）。
 */
public interface MessageRepository extends JpaRepository<Message, UUID>, MessageRepositoryCustom {

    /**
     * 打开会话即已读：批量写入「对方发出且未读」消息的 read_at（幂等）。
     * 返回本次置读的消息条数（badge 相应减少）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Message m
            SET m.readAt = :now, m.updatedAt = :now
            WHERE m.conversationId = :conversationId
              AND m.senderId <> :userId
              AND m.readAt IS NULL
            """)
    int markConversationRead(@Param("conversationId") UUID conversationId,
                             @Param("userId") UUID userId,
                             @Param("now") Instant now);

    /** 某会话内当前用户未读消息数（幂等创建响应的 unread_count 数据源）。 */
    long countByConversationIdAndSenderIdNotAndReadAtIsNull(UUID conversationId, UUID senderId);
}
