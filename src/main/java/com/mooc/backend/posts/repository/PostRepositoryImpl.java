package com.mooc.backend.posts.repository;

import com.mooc.backend.posts.api.PostStatsView;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 帖子列表聚合查询的 native SQL 实现。
 *
 * <p>聚合口径（与 posts spec 对齐）：
 * - {@code comment_count}：该帖全部评论（含回复）且排除软删（子查询加 {@code c.deleted = false}）。
 * - {@code up_vote_count}：仅计 {@code vote_type = 'UP'}（忽略 DOWN）。
 * - {@code bookmark_count}：有效收藏（子查询加 {@code b.deleted = false} 兜底）。
 *
 * <p>关键实现约束（已用真实 MySQL 验证）：
 * 1. 必须用<b>相关子查询</b>分别聚合 comments / votes / bookmarks。
 *    早期版本用多个 {@code LEFT JOIN} 再 {@code COUNT}，会因多对多叉乘把计数成倍放大
 *    （例如同时有投票与收藏时 comment_count / bookmark_count 会被放大）。子查询各自独立计数，结果正确。
 * 2. 不得写 {@code CAST(p.id AS VARCHAR(36))}：MySQL 的 {@code CAST} 只接受 {@code CHAR(n)}，
 *    不接受 {@code VARCHAR(n)}，原写法会在 MySQL 上直接报语法错误。故直接取 {@code p.id} 原始值，
 *    Hibernate 6 默认把 {@code UUID} 主键映射为 {@code binary(16)}，在 Java 侧把 {@code byte[]} 还原为 {@link UUID}。
 *
 * <p>游标（仅 latest）：按 {@code (created_at, id) < (cursorTs, cursorId)} 截断；
 * id 比较统一按 {@code binary(16)} 字节序（与 Hibernate 存储布局一致），由调用方把 cursorId 转为 16 字节传入。
 */
@Repository
public class PostRepositoryImpl implements PostRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    private static final String SELECT_CLAUSE = """
            SELECT p.id AS post_id,
                   (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.deleted = false) AS comment_count,
                   (SELECT COALESCE(SUM(CASE WHEN v.vote_type = 'UP' THEN 1 ELSE 0 END), 0)
                        FROM votes v WHERE v.post_id = p.id AND v.deleted = false) AS up_vote_count,
                   (SELECT COUNT(*) FROM bookmarks b WHERE b.post_id = p.id AND b.deleted = false) AS bookmark_count
            FROM posts p
            """;

    private static final String ORDER_LATEST = "p.created_at DESC, p.id DESC";
    private static final String ORDER_TOP = "up_vote_count DESC, p.created_at DESC, p.id DESC";
    private static final String ORDER_COMMENTED = "comment_count DESC, p.created_at DESC, p.id DESC";

    @Override
    public List<PostStatsView> findPublishedStats(PostSort sort, int size, int offset,
                                                  Instant cursorTs, UUID cursorId, boolean useCursor) {
        String where = "WHERE p.status = 'PUBLISHED' AND p.deleted = false";
        if (useCursor) {
            where += cursorPredicate();
        }
        return query(SELECT_CLAUSE + where + " GROUP BY p.id", sort, size, offset, cursorTs, cursorId, useCursor);
    }

    @Override
    public List<PostStatsView> findMyStats(UUID authorId, PostSort sort, int size, int offset,
                                           Instant cursorTs, UUID cursorId, boolean useCursor) {
        String where = "WHERE p.author_id = :authorId AND p.deleted = false";
        if (useCursor) {
            where += cursorPredicate();
        }
        return query(SELECT_CLAUSE + where + " GROUP BY p.id", sort, size, offset, cursorTs, cursorId, useCursor, authorId);
    }

    @Override
    public List<PostStatsView> findPublishedByLocation(PostSort sort, int size, int offset,
                                                       String cityId, String spotId) {
        String join = spotId != null ? " JOIN post_spots ps ON p.id = ps.post_id " : "";
        String where = "WHERE p.status = 'PUBLISHED' AND p.deleted = false"
                + (cityId != null ? " AND p.city_id = :cityId" : "")
                + (spotId != null ? " AND ps.spot_slug = :spotId" : "");
        String order = switch (sort) {
            case TOP -> ORDER_TOP;
            case MOST_COMMENTED -> ORDER_COMMENTED;
            default -> ORDER_LATEST;
        };
        String full = SELECT_CLAUSE + join + where + " GROUP BY p.id ORDER BY " + order
                + " LIMIT :limit OFFSET :offset";
        var q = em.createNativeQuery(full);
        q.setParameter("limit", size);
        q.setParameter("offset", offset);
        if (cityId != null) {
            q.setParameter("cityId", cityId);
        }
        if (spotId != null) {
            q.setParameter("spotId", spotId);
        }
        return mapStats(q.getResultList());
    }

    @Override
    public long countPublishedByLocation(String cityId, String spotId) {
        String join = spotId != null ? " JOIN post_spots ps ON p.id = ps.post_id " : "";
        String where = "WHERE p.status = 'PUBLISHED' AND p.deleted = false"
                + (cityId != null ? " AND p.city_id = :cityId" : "")
                + (spotId != null ? " AND ps.spot_slug = :spotId" : "");
        var q = em.createNativeQuery("SELECT COUNT(DISTINCT p.id) FROM posts p " + join + where);
        if (cityId != null) {
            q.setParameter("cityId", cityId);
        }
        if (spotId != null) {
            q.setParameter("spotId", spotId);
        }
        return ((Number) q.getSingleResult()).longValue();
    }

    private String cursorPredicate() {
        return " AND (p.created_at < :curTs OR (p.created_at = :curTs AND p.id < :curId))";
    }

    private List<PostStatsView> query(String sql, PostSort sort, int size, int offset,
                                      Instant cursorTs, UUID cursorId, boolean useCursor, UUID... authorId) {
        String order = switch (sort) {
            case TOP -> ORDER_TOP;
            case MOST_COMMENTED -> ORDER_COMMENTED;
            default -> ORDER_LATEST;
        };
        String full = sql + " ORDER BY " + order + " LIMIT :limit" + (useCursor ? "" : " OFFSET :offset");
        var q = em.createNativeQuery(full);
        q.setParameter("limit", size);
        if (useCursor) {
            q.setParameter("curTs", cursorTs);
            q.setParameter("curId", uuidToBytes(cursorId));
        } else {
            q.setParameter("offset", offset);
        }
        if (authorId.length > 0) {
            q.setParameter("authorId", authorId[0]);
        }
        return mapStats(q.getResultList());
    }

    private List<PostStatsView> mapStats(List<?> rows) {
        List<PostStatsView> out = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            UUID id = toUuid(r[0]);
            long commentCount = ((Number) r[1]).longValue();
            long upVoteCount = ((Number) r[2]).longValue();
            long bookmarkCount = ((Number) r[3]).longValue();
            out.add(new PostStatsView(id, commentCount, upVoteCount, bookmarkCount));
        }
        return out;
    }

    /** 兼容 Hibernate 6 默认 {@code binary(16)} 存储与可能的字符串存储，统一还原为 UUID。 */
    private static UUID toUuid(Object idVal) {
        if (idVal instanceof UUID u) {
            return u;
        }
        if (idVal instanceof byte[] b && b.length == 16) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new UUID(bb.getLong(), bb.getLong());
        }
        if (idVal instanceof String s) {
            return UUID.fromString(s);
        }
        throw new IllegalStateException("Unexpected post id type: " + (idVal == null ? "null" : idVal.getClass()));
    }

    /** 把 UUID 编码为 16 字节大端，与 Hibernate 6 的 {@code binary(16)} 存储布局一致，用于游标比较。 */
    private static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
