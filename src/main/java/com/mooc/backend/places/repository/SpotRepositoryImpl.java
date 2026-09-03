package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.Spot;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 景点列表筛选原生实现。
 *
 * <p>{@code tag} 过滤命中 {@code tags} JSON 数组（{@code JSON_CONTAINS(tags, '"<tag>"')}）。
 * {@code q} 关键词模糊匹配 {@code name_en} / {@code name_zh}。{@code category} 忽略大小写（UPPER 比较）。
 */
@Repository
public class SpotRepositoryImpl implements SpotRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Spot> search(String city, String category, String tag, String q, String sort, Pageable pageable) {
        String where = "WHERE s.deleted = false AND s.status = 'PUBLISHED'";
        if (isNotBlank(city)) {
            where += " AND s.city_slug = :city";
        }
        if (isNotBlank(category)) {
            where += " AND s.category = UPPER(:category)";
        }
        if (isNotBlank(tag)) {
            where += " AND JSON_CONTAINS(s.tags, :tagJson)";
        }
        if (isNotBlank(q)) {
            where += " AND (s.name_en LIKE :q OR s.name_zh LIKE :q)";
        }
        // 排序键在数据集内可能大面积并列（如 view_count 全为 0），此时 MySQL 不保证返回顺序，
        // 缺少唯一 tie-breaker 会导致 offset 分页的相邻页返回重叠行或永久漏行。
        // slug 为复合 slug 且全局唯一、创建后不可变，是确定性 tie-breaker 的合适选择。
        String order = "s.view_count DESC, s.slug ASC";
        if ("hidden".equals(sort)) {
            order = "s.hidden_gem DESC, s.view_count DESC, s.slug ASC";
        }
        String sql = "SELECT s.* FROM spots s " + where + " ORDER BY " + order
                + " LIMIT :limit OFFSET :offset";
        var query = em.createNativeQuery(sql, Spot.class);
        if (isNotBlank(city)) {
            query.setParameter("city", city);
        }
        if (isNotBlank(category)) {
            query.setParameter("category", category);
        }
        if (isNotBlank(tag)) {
            query.setParameter("tagJson", jsonQuote(tag));
        }
        if (isNotBlank(q)) {
            query.setParameter("q", "%" + q + "%");
        }
        query.setParameter("limit", pageable.getPageSize());
        query.setParameter("offset", pageable.getOffset());
        return query.getResultList();
    }

    @Override
    public List<Spot> ranking(String type, Pageable pageable) {
        String orderBy;
        String from;
        if ("bookmarks".equals(type)) {
            // 主排序按收藏计数（SQL 每次执行实时聚合；排行榜端点响应经 RankingCacheService 缓存，
            // 近实时 ≤5 分钟窗口，收藏切换 / 景点写操作即时失效）
            // 并列时以 view_count、再以 slug 作 tiebreaker，保证结果确定（契约仅要求按收藏数 DESC）
            orderBy = "ORDER BY COALESCE(b.cnt, 0) DESC, s.view_count DESC, s.slug ASC";
            from = "FROM spots s LEFT JOIN (SELECT spot_slug, COUNT(*) AS cnt FROM spot_bookmarks GROUP BY spot_slug) b "
                    + "ON s.slug = b.spot_slug";
        } else if ("rating".equals(type)) {
            // MySQL 不支持 NULLS LAST 语法：用 IS NULL ASC 把无评分排到末尾
            // slug 作 tie-breaker：评分并列 / 同为 null 时保证顺序确定
            orderBy = "ORDER BY s.rating IS NULL ASC, s.rating DESC, s.slug ASC";
            from = "FROM spots s";
        } else {
            // popular（默认）：按访问计数降序；并列时以 slug 保证顺序确定
            orderBy = "ORDER BY s.view_count DESC, s.slug ASC";
            from = "FROM spots s";
        }
        String sql = "SELECT s.* " + from
                + " WHERE s.deleted = false AND s.status = 'PUBLISHED' " + orderBy + " LIMIT :limit";
        var query = em.createNativeQuery(sql, Spot.class);
        query.setParameter("limit", pageable.getPageSize());
        return query.getResultList();
    }

    @Override
    public long countSearch(String city, String category, String tag, String q) {
        String where = "WHERE s.deleted = false AND s.status = 'PUBLISHED'";
        if (isNotBlank(city)) {
            where += " AND s.city_slug = :city";
        }
        if (isNotBlank(category)) {
            where += " AND s.category = UPPER(:category)";
        }
        if (isNotBlank(tag)) {
            where += " AND JSON_CONTAINS(s.tags, :tagJson)";
        }
        if (isNotBlank(q)) {
            where += " AND (s.name_en LIKE :q OR s.name_zh LIKE :q)";
        }
        String sql = "SELECT COUNT(*) FROM spots s " + where;
        var query = em.createNativeQuery(sql);
        if (isNotBlank(city)) {
            query.setParameter("city", city);
        }
        if (isNotBlank(category)) {
            query.setParameter("category", category);
        }
        if (isNotBlank(tag)) {
            query.setParameter("tagJson", jsonQuote(tag));
        }
        if (isNotBlank(q)) {
            query.setParameter("q", "%" + q + "%");
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 把字符串包成合法的 JSON 字符串字面量（转义反斜杠与双引号），供 JSON_CONTAINS 使用。 */
    private static String jsonQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
