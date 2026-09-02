package com.mooc.backend.places.domain;

import java.util.Locale;

/**
 * 城市 slug 生成器：由英文名 {@code name} 自动 kebab-case 化，全小写。
 *
 * <p>规则：trim 后小写；非 {@code [a-z0-9]} 的连续序列折叠为单个 {@code -}；去除首尾 {@code -}。
 * 例：{@code Hangzhou→hangzhou}、{@code Xi'an→xi-an}、{@code New York→new-york}。
 * 供种子导入等写入路径使用；数据库 {@code UNIQUE(slug)} 为最终唯一性兜底。
 */
public final class CitySlugs {

    private CitySlugs() {
        // utility
    }

    public static String slugify(String name) {
        return name.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }
}
