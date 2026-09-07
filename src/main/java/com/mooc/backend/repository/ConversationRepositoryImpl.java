package com.mooc.backend.repository;
import com.mooc.backend.entity.Conversation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话列表游标分页的 native SQL 实现（Task 1.1）。
 *
 * <p>关键实现约束（与 {@code NotificationRepositoryImpl} 同款，已在真实 MySQL 验证）：
 * <ul>
 *   <li>id 比较按 {@code binary(16)} 字节序：调用方传入 {@link UUID}，
 *       实现侧转为 16 字节大端传入，避免 Java {@code UUID.compareTo}
 *       （有符号 long 语义）与 MySQL 字节序不一致导致游标翻页漏行 / 重叠。</li>
 *   <li>可见性延迟：{@code (last_message_id IS NULL AND initiator_id <> 当前用户)} 的会话
 *       不可见——首条消息发出前收件人的列表不出现空会话（design.md ADR-3）。</li>
 *   <li>排序键 {@code COALESCE(last_message_at, created_at)}：空会话以创建时间参与全序，
 *       否则 NULL 参与游标比较无法良定义；代价是该表达式暂不走
 *       {@code idx_conversations_*} 的第二列（P0 可接受，P2 评估冗余排序列）。</li>
 * </ul>
 */
@Repository
public class ConversationRepositoryImpl implements ConversationRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Conversation> findPage(UUID userId, int limit,
                                       Instant cursorTs, UUID cursorId, boolean useCursor) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.* FROM conversations c
                WHERE (c.user_low_id = :userId OR c.user_high_id = :userId)
                  AND (c.last_message_id IS NOT NULL OR c.initiator_id = :userId)
                """);
        if (useCursor) {
            sql.append("""
                  AND (COALESCE(c.last_message_at, c.created_at) < :curTs
                       OR (COALESCE(c.last_message_at, c.created_at) = :curTs AND c.id < :curId))
                """);
        }
        sql.append(" ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC LIMIT :limit");

        var query = em.createNativeQuery(sql.toString(), Conversation.class);
        query.setParameter("userId", userId);
        query.setParameter("limit", limit);
        if (useCursor) {
            query.setParameter("curTs", cursorTs);
            query.setParameter("curId", uuidToBytes(cursorId));
        }
        return query.getResultList();
    }

    /** 把 UUID 编码为 16 字节大端，与 Hibernate 6 的 {@code binary(16)} 存储布局一致，用于游标比较。 */
    private static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
