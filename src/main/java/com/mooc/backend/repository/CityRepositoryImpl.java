package com.mooc.backend.repository;

import com.mooc.backend.entity.City;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 城市关键词搜索原生实现（change: ai-semantic-search，design.md D3）。
 *
 * <p>LIKE 通配符由 {@link LikePatterns} 转义；{@code ORDER BY name, id} 保证确定性
 * （id 为 UUID 主键，作稳定 tie-breaker）。
 */
@Repository
public class CityRepositoryImpl implements CityRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<City> searchByKeyword(String q, int limit) {
        String sql = """
                SELECT c.* FROM cities c
                WHERE c.deleted = false
                  AND (c.name LIKE :q ESCAPE '\\\\' OR c.name_zh LIKE :q ESCAPE '\\\\')
                ORDER BY c.name ASC, c.id ASC
                LIMIT :limit
                """;
        var query = em.createNativeQuery(sql, City.class);
        query.setParameter("q", LikePatterns.contains(q));
        query.setParameter("limit", limit);
        return query.getResultList();
    }
}
