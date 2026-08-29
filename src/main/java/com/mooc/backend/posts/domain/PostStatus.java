package com.mooc.backend.posts.domain;

/**
 * 帖子生命周期状态。
 *
 * <p>当前仅区分草稿与已发布：DRAFT 仅作者本人可见，PUBLISHED 进入公开列表 / 详情。
 * 无审核流，故不引入更多状态（YAGNI）。公开列表只返回 PUBLISHED。
 */
public enum PostStatus {
    DRAFT,
    PUBLISHED
}
