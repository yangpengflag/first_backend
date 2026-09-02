package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.City;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 城市仓储。
 *
 * <p>{@code findBySlug}（含软删行）供种子导入幂等判重用——已存在（含已软删）即视为已导入、跳过重灌；
 * 对外只读 API 一律经 {@code findBySlugAndDeletedFalse} / {@code findByDeletedFalse} 过滤软删。
 * 列表无筛选维度（省份 / 标签 / 浏览量已随 {@code city-module} 精简移除），仅按 {@code name} 排序分页。
 */
public interface CityRepository extends JpaRepository<City, UUID> {

    Optional<City> findBySlugAndDeletedFalse(String slug);

    Optional<City> findBySlug(String slug);

    Page<City> findByDeletedFalse(Pageable pageable);
}
