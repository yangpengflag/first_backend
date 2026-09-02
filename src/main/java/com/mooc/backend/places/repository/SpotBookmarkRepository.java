package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.SpotBookmark;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * 景点收藏仓储。一人一景点唯一约束由 {@code spot_bookmarks} 表保证；取消收藏经物理删除实现。
 */
public interface SpotBookmarkRepository extends JpaRepository<SpotBookmark, UUID> {

    /** 某用户的收藏，按收藏时间倒序（最近在前）。 */
    Page<SpotBookmark> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * 某用户的收藏，仅含已发布（PUBLISHED 且未软删）景点，按收藏时间倒序。
     * 用于「我的收藏列表」：在 SQL 层即过滤，保证分页 {@code total} 与返回内容一致
     * （避免先分页后内存过滤导致 total 偏大、前端分页错乱）。用 JPQL 让 Hibernate 负责
     * {@code created_at} 列名解析，避免原生 SQL 字面量列名不一致。
     */
    @Query("SELECT b FROM SpotBookmark b, Spot s "
            + "WHERE s.slug = b.spotSlug AND b.userId = :userId "
            + "AND s.deleted = false AND s.status = com.mooc.backend.places.domain.SpotStatus.PUBLISHED "
            + "ORDER BY b.createdAt DESC")
    Page<SpotBookmark> findPublishedByUserId(UUID userId, Pageable pageable);

    /** 取某用户对某景点的收藏（用于切换 / 状态查询）。 */
    Optional<SpotBookmark> findBySpotSlugAndUserId(String spotSlug, UUID userId);
}
