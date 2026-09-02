package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 景点仓储。
 *
 * <p>软删除通过查询层显式过滤实现。列表多条件筛选（城市 / 分类 / 标签 / 关键词 / 排序 / 分页）由
 * {@link SpotRepositoryCustom} 原生实现（公开列表仅返回 PUBLISHED）。
 * 城市 / 周边 POI 查询均带 {@code status} 参数，公开读仅返回 PUBLISHED。
 * {@code findByCitySlug...} 供城市详情 Top POI 与景点详情周边 POI 使用。
 */
public interface SpotRepository extends JpaRepository<Spot, UUID>, SpotRepositoryCustom {

    /** 按复合 slug 查询，显式排除软删行（保住"软删行 404"语义）。 */
    Optional<Spot> findBySlugAndDeletedFalse(String slug);

    /** 按复合 slug 查询（含软删行），供种子导入幂等判重用——已存在即视为已导入。 */
    Optional<Spot> findBySlug(String slug);

    /** 城市详情 Top POI：按 view_count 降序取前 N（仅 PUBLISHED）。 */
    List<Spot> findByCitySlugAndStatusAndDeletedFalse(String citySlug, SpotStatus status, Pageable pageable);

    /** 景点详情周边 POI：同城、排除自身，按 view_count 降序取前 N（仅 PUBLISHED）。 */
    List<Spot> findByCitySlugAndStatusAndDeletedFalseAndIdNot(String citySlug, SpotStatus status, UUID excludeId, Pageable pageable);

    /** 城市详情 / 列表项 spotCount（仅 PUBLISHED）。 */
    long countByCitySlugAndStatusAndDeletedFalse(String citySlug, SpotStatus status);

    /** 批量按 slug 查（收藏列表用），仅未软删行。 */
    List<Spot> findBySlugInAndDeletedFalse(List<String> slugs);

    /** 详情访问计数 +1（异步防刷在 {@code ViewCountService} 中调度）。 */
    @Transactional
    @Modifying
    @Query("UPDATE Spot s SET s.viewCount = s.viewCount + 1 WHERE s.slug = :slug AND s.deleted = false")
    void incrementViewCountBySlug(@Param("slug") String slug);
}
