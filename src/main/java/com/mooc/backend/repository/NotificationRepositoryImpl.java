package com.mooc.backend.repository;
import com.mooc.backend.entity.Notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通知列表游标分页的 native SQL 实现。
 *
 * <p>关键实现约束（与 {@code PostRepositoryImpl} 同款，已在真实 MySQL 验证）：
 * <ul>
 *   <li>id 比较按 {@code binary(16)} 字节序：调用方传入 {@link UUID}，
 *       实现侧转为 16 字节大端与 Hibernate 6 的存储布局一致，
 *       避免 Java {@code UUID.compareTo}（有符号 long 语义）与 MySQL 字节序不一致
 *       导致游标翻页漏行 / 重叠。</li>
 *   <li>结果以 {@code Notification.class} 实体映射返回，供服务层直接组装 DTO。</li>
 * </ul>
 */
@Repository
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Notification> findPage(UUID recipientId, boolean unreadOnly, int limit,
                                       Instant cursorTs, UUID cursorId, boolean useCursor) {
        StringBuilder sql = new StringBuilder("SELECT n.* FROM notifications n WHERE n.recipient_id = :recipientId");
        if (unreadOnly) {
            sql.append(" AND n.read_at IS NULL");
        }
        if (useCursor) {
            sql.append(" AND (n.last_interacted_at < :curTs")
               .append(" OR (n.last_interacted_at = :curTs AND n.id < :curId))");
        }
        sql.append(" ORDER BY n.last_interacted_at DESC, n.id DESC LIMIT :limit");

        var query = em.createNativeQuery(sql.toString(), Notification.class);
        query.setParameter("recipientId", recipientId);
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
