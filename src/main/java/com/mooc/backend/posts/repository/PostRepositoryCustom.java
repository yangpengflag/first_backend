package com.mooc.backend.posts.repository;

import com.mooc.backend.posts.api.PostStatsView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 帖子列表聚合查询（自定义实现）。
 *
 * <p>native SQL：LEFT JOIN comments / votes / bookmarks + GROUP BY post.id，在 SQL 层按计数排序，
 * 确保排序与分页精确。Post 模块读取其它模块表属有意打破模块边界（同库单查询）。
 * 详见 {@code PostRepositoryImpl}。
 */
public interface PostRepositoryCustom {

    /** 公开列表聚合（仅 PUBLISHED）。cursor 模式 useCursor=true 时忽略 offset。 */
    List<PostStatsView> findPublishedStats(PostSort sort, int size, int offset,
                                           Instant cursorTs, UUID cursorId, boolean useCursor);

    /** 我的帖子聚合（含 DRAFT）。cursor 模式 useCursor=true 时忽略 offset。 */
    List<PostStatsView> findMyStats(UUID authorId, PostSort sort, int size, int offset,
                                    Instant cursorTs, UUID cursorId, boolean useCursor);

    /** 按地点过滤的公开列表聚合（仅 PUBLISHED）：cityId 精确匹配，spotId 命中 spot_ids 数组（JSON_CONTAINS）。offset 分页。 */
    List<PostStatsView> findPublishedByLocation(PostSort sort, int size, int offset,
                                                String cityId, String spotId);

    /** 按地点过滤的 PUBLISHED 总数（offset 分页 total 用）。 */
    long countPublishedByLocation(String cityId, String spotId);
}
