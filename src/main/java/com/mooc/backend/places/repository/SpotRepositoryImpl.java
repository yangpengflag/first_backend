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
        String where = "WHERE s.deleted = false";
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
        String order = "s.view_count DESC";
        if ("hidden".equals(sort)) {
            order = "s.hidden_gem DESC, s.view_count DESC";
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
            query.setParameter("tagJson", "\"" + tag + "\"");
        }
        if (isNotBlank(q)) {
            query.setParameter("q", "%" + q + "%");
        }
        query.setParameter("limit", pageable.getPageSize());
        query.setParameter("offset", pageable.getOffset());
        return query.getResultList();
    }

    @Override
    public long countSearch(String city, String category, String tag, String q) {
        String where = "WHERE s.deleted = false";
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
            query.setParameter("tagJson", "\"" + tag + "\"");
        }
        if (isNotBlank(q)) {
            query.setParameter("q", "%" + q + "%");
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
