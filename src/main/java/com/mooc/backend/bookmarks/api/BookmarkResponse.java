package com.mooc.backend.bookmarks.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.Set;
import java.util.UUID;

/**
 * 收藏切换响应（snake_case 白名单，对齐 backend-conventions）。
 *
 * <p>{@code bookmarked} 表示切换后该用户是否已收藏。继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class BookmarkResponse extends BaseResponse {

    @JsonProperty("post_id") private final UUID postId;
    @JsonProperty("bookmarked") private final boolean bookmarked;

    public BookmarkResponse(UUID postId, boolean bookmarked) {
        super();
        this.postId = postId;
        this.bookmarked = bookmarked;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of("post_id", "bookmarked", "request_id");

    public static BookmarkResponse from(UUID postId, boolean bookmarked) {
        return new BookmarkResponse(postId, bookmarked);
    }

    public UUID getPostId() {
        return postId;
    }

    public boolean isBookmarked() {
        return bookmarked;
    }
}
