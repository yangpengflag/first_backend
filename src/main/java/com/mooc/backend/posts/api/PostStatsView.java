package com.mooc.backend.posts.api;

import java.util.UUID;

/**
 * 列表聚合读模型：帖子 id + 三项实时计数。
 *
 * <p>计数由 {@code PostRepository} 的 native 聚合查询（相关子查询分别聚合 comments / votes / bookmarks，避免多表 JOIN 叉乘）
 * 实时得出，<b>不</b>在 {@code Post} 实体冗余存储。不含 {@code createdAt}——
 * 游标编码所需的时间戳从按 id 取回的 {@code Post} 实体获取，避免 native 查询的 UUID / Instant 类型转换歧义。
 */
public class PostStatsView {

    private final UUID postId;
    private final long commentCount;
    private final long upVoteCount;
    private final long bookmarkCount;

    public PostStatsView(UUID postId, long commentCount, long upVoteCount, long bookmarkCount) {
        this.postId = postId;
        this.commentCount = commentCount;
        this.upVoteCount = upVoteCount;
        this.bookmarkCount = bookmarkCount;
    }

    public UUID postId() {
        return postId;
    }

    public long commentCount() {
        return commentCount;
    }

    public long upVoteCount() {
        return upVoteCount;
    }

    public long bookmarkCount() {
        return bookmarkCount;
    }
}
