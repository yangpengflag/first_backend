package com.mooc.backend.repository;

import com.mooc.backend.entity.City;

import java.util.List;

/**
 * 城市关键词搜索（change: ai-semantic-search，tasks 1.1）。
 *
 * <p>混合搜索的关键词腿：LIKE {@code name} / {@code name_zh}（注意：cities 无 {@code name_en} 列，
 * 英文名列即 {@code name}）。仅未软删行。
 */
public interface CityRepositoryCustom {

    /** 按关键词（名称中英文包含匹配，通配符已转义）取前 {@code limit} 个未软删城市。 */
    List<City> searchByKeyword(String q, int limit);
}
