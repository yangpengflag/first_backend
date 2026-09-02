package com.mooc.backend.places.domain;

/**
 * 景点发布状态，与 {@code PostStatus} 对齐。
 *
 * <p>DRAFT 为 CMS 草稿，不对外公开（列表 / 详情 / 城市 Top POI / spot_count 均过滤）；
 * 仅 PUBLISHED 出现在公开读路径。HIDDEN / 精选由既有 {@code hiddenGem} / {@code featured} 承载，不进 status。
 */
public enum SpotStatus {
    DRAFT,
    PUBLISHED
}
