package com.mooc.backend.repository;
import com.mooc.backend.entity.Message;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息历史与未读聚合的 native SQL 实现（Task 1.1）。
 *
 * <p>关键实现约束（与 {@code NotificationRepositoryImpl} 同款，已在真实 MySQL 验证）：
 * <ul>
 *   <li>id 比较按 {@code binary(16)} 字节序：调用方传入 {@link UUID}，
 *       实现侧转为 16 字节大端传入，避免 Java {@code UUID.compareTo}
 *       （有符号 long 语义）与 MySQL 字节序不一致导致游标翻页漏行 / 重叠。</li>
 *   <li>元组结果中 {@code binary(16)} 列由 JDBC 返回 {@code byte[]}，
 *       以 {@link #bytesToUuid} 反解（Hibernate 6 UUID 存储布局的逆变换）。</li>
 *   <li>未读统计按会话有序对方向拆为两条同构子查询（UNION ALL），
 *       分别命中 {@code idx_conversations_low / idx_conversations_high}；
 *       同一用户在任一会话中只占一个方向，无重复计数。</li>
 * </ul>
 */
@Repository
public class MessageRepositoryImpl implements MessageRepositoryCustom {

    /** 「对方发出且未读」的公共过滤段（conversation 经会话有序对方向关联），%s 填方向列。 */
    private static final String UNREAD_DIRECTION_SQL = """
            FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            WHERE c.%s = :userId
              AND m.sender_id <> :userId
              AND m.read_at IS NULL
            """;

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Message> findPage(UUID conversationId, int limit,
                                  Instant cursorTs, UUID cursorId, boolean useCursor) {
        StringBuilder sql = new StringBuilder("SELECT m.* FROM messages m WHERE m.conversation_id = :conversationId");
        if (useCursor) {
            sql.append("""
                  AND (m.created_at < :curTs
                       OR (m.created_at = :curTs AND m.id < :curId))
                """);
        }
        sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT :limit");

        var query = em.createNativeQuery(sql.toString(), Message.class);
        query.setParameter("conversationId", conversationId);
        query.setParameter("limit", limit);
        if (useCursor) {
            query.setParameter("curTs", cursorTs);
            query.setParameter("curId", uuidToBytes(cursorId));
        }
        return query.getResultList();
    }

    @Override
    public Map<UUID, Long> countUnreadByConversation(UUID userId) {
        String sql = """
                SELECT m.conversation_id, COUNT(*) AS unread_count
                """
                + UNREAD_DIRECTION_SQL.formatted("user_low_id")
                + """
                GROUP BY m.conversation_id
                UNION ALL
                SELECT m.conversation_id, COUNT(*) AS unread_count
                """
                + UNREAD_DIRECTION_SQL.formatted("user_high_id")
                + """
                GROUP BY m.conversation_id
                """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("userId", userId)
                .getResultList();

        Map<UUID, Long> unreadByConversation = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID conversationId = bytesToUuid((byte[]) row[0]);
            long count = ((Number) row[1]).longValue();
            unreadByConversation.merge(conversationId, count, Long::sum);
        }
        return unreadByConversation;
    }

    @Override
    public long countUnreadTotal(UUID userId) {
        String sql = """
                SELECT COALESCE(SUM(t.cnt), 0) FROM (
                  SELECT COUNT(*) AS cnt
                  """
                + UNREAD_DIRECTION_SQL.formatted("user_low_id")
                + """
                  UNION ALL
                  SELECT COUNT(*) AS cnt
                  """
                + UNREAD_DIRECTION_SQL.formatted("user_high_id")
                + """
                ) t
                """;

        Object result = em.createNativeQuery(sql)
                .setParameter("userId", userId)
                .getSingleResult();
        return ((Number) result).longValue();
    }

    /** 把 UUID 编码为 16 字节大端，与 Hibernate 6 的 {@code binary(16)} 存储布局一致，用于游标比较。 */
    private static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /** {@link #uuidToBytes} 的逆变换：native 元组中的 {@code binary(16)} 反解为 UUID。 */
    private static UUID bytesToUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
