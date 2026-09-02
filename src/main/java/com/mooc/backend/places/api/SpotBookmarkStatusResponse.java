package com.mooc.backend.places.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.Set;

/**
 * 景点收藏状态 / 切换响应（snake_case 白名单，对齐 backend-conventions）。
 *
 * <p>{@code bookmarked} 表示当前用户是否已收藏（切换后状态）。继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SpotBookmarkStatusResponse extends BaseResponse {

    @JsonProperty("spot_slug") private final String spotSlug;
    @JsonProperty("bookmarked") private final boolean bookmarked;

    public SpotBookmarkStatusResponse(String spotSlug, boolean bookmarked) {
        super();
        this.spotSlug = spotSlug;
        this.bookmarked = bookmarked;
    }

    public static SpotBookmarkStatusResponse from(String spotSlug, boolean bookmarked) {
        return new SpotBookmarkStatusResponse(spotSlug, bookmarked);
    }

    public String getSpotSlug() {
        return spotSlug;
    }

    public boolean isBookmarked() {
        return bookmarked;
    }
}
