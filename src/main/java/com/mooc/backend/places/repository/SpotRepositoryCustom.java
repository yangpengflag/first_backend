package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.Spot;

import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 景点列表筛选查询（原生实现）。
 *
 * <p>筛选维度：{@code city}（city_slug 精确）、{@code category}（枚举名，忽略大小写）、
 * {@code tag}（命中 tags JSON 数组）、{@code q}（name_en / name_zh 模糊）。
 * 排序：{@code popular}（默认，view_count DESC）/ {@code hidden}（hidden_gem DESC, view_count DESC）。
 * 分页走 offset 模式，total 由 {@link #countSearch} 单独计算。
 */
public interface SpotRepositoryCustom {

    List<Spot> search(String city, String category, String tag, String q, String sort, Pageable pageable);

    long countSearch(String city, String category, String tag, String q);
}
