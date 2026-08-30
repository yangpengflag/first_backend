package com.mooc.backend.bookmarks.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.posts.api.PostSummary;

import java.util.Set;
import java.util.UUID;

/**
 * 收藏列表项（列表项，不含 request_id，与 {@code Page<PostSummary>} 同构但不继承 BaseResponse）。
 *
 * <p>{@code available} 标记原帖子当前是否可访问（未软删且 {@code PUBLISHED}）；不可用时
 * {@code post} 为 {@code null}，前端据以渲染「帖子已不可用」占位，避免静默丢弃用户收藏。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class BookmarkSummary {

    @JsonProperty("post_id") private final UUID postId;
    @JsonProperty("available") private final boolean available;
    @JsonProperty("post") private final PostSummary post;

    public BookmarkSummary(UUID postId, boolean available, PostSummary post) {
        this.postId = postId;
        this.available = available;
        this.post = post;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of("post_id", "available", "post");

    public UUID getPostId() {
        return postId;
    }

    public boolean isAvailable() {
        return available;
    }

    public PostSummary getPost() {
        return post;
    }
}
