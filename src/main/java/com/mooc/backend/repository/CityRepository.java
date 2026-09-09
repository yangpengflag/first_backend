package com.mooc.backend.repository;

import com.mooc.backend.entity.City;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 城市仓储。
 *
 * <p>{@code findBySlug}（含软删行）供种子导入幂等判重用——已存在（含已软删）即视为已导入、跳过重灌；
 * 对外只读 API 一律经 {@code findBySlugAndDeletedFalse} / {@code findByDeletedFalse} 过滤软删。
 * 列表无筛选维度（省份 / 标签 / 浏览量已随 {@code city-module} 精简移除），仅按 {@code name} 排序分页。
 */
public interface CityRepository extends JpaRepository<City, UUID>, CityRepositoryCustom {

    Optional<City> findBySlugAndDeletedFalse(String slug);

    Optional<City> findBySlug(String slug);

    Page<City> findByDeletedFalse(Pageable pageable);

    /**
     * 增量索引同步（change: ai-rag-incremental-sync）：取 {@code updated_at} 严格晚于水位线的行。
     * <b>不</b>过滤 {@code deleted}——软删行必须进入变更集，其既有检索块才能被清除。
     */
    List<City> findByUpdatedAtAfter(Instant updatedAt);
}
