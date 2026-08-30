package com.mooc.backend.bookmarks.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.Set;
import java.util.UUID;

/**
 * 收藏状态响应（snake_case 白名单，对齐 backend-conventions）。
 *
 * <p>{@code bookmarked} 表示当前用户是否已收藏该帖。继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class BookmarkStatusResponse extends BaseResponse {

    @JsonProperty("post_id") private final UUID postId;
    @JsonProperty("bookmarked") private final boolean bookmarked;

    public BookmarkStatusResponse(UUID postId, boolean bookmarked) {
        super();
        this.postId = postId;
        this.bookmarked = bookmarked;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of("post_id", "bookmarked", "request_id");

    public static BookmarkStatusResponse from(UUID postId, boolean bookmarked) {
        return new BookmarkStatusResponse(postId, bookmarked);
    }

    public UUID getPostId() {
        return postId;
    }

    public boolean isBookmarked() {
        return bookmarked;
    }
}
