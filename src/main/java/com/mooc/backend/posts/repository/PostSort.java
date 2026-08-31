package com.mooc.backend.posts.repository;

/**
 * 列表排序键。与控制器 {@code sort} 查询参数映射：
 * {@code latest}（默认，created_at DESC）/ {@code top}（up_vote_count DESC）/ {@code most_commented}（comment_count DESC）。
 */
public enum PostSort {

    LATEST,
    TOP,
    MOST_COMMENTED;

    public static PostSort from(String value) {
        if (value == null) {
            return LATEST;
        }
        return switch (value) {
            case "top" -> TOP;
            case "most_commented" -> MOST_COMMENTED;
            default -> LATEST;
        };
    }
}
