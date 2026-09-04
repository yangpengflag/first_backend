package com.mooc.backend.notifications.domain;

/**
 * 通知类型（Task 1.1）。数据源仅 posts 模块互动；spot 互动无 creator 主体，不产生通知。
 *
 * <p>持久化统一 {@code EnumType.STRING}（禁止 ORDINAL），与 votes 等既有实体约定一致。
 */
public enum NotificationType {

    /** 帖子被投 UP 点赞。 */
    POST_LIKED,

    /** 帖子被评论 / 被回复（MVP 文案统一 "commented on your post"，不区分顶层与回复）。 */
    POST_COMMENTED,

    /** 帖子被收藏（P1）。 */
    POST_BOOKMARKED
}
