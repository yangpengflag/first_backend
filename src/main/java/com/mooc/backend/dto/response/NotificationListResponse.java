package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.List;

/**
 * 通知列表统一分页信封（对齐 {@code PostListResponse} 既有惯例，Task 3.2）。
 *
 * <p>始终返回 {@code items} / {@code next_cursor} / {@code has_more}；
 * cursor 模式不输出 page / size / total（notifications 仅支持游标分页）。
 * {@code last_interacted_at} 倒序，空列表输出 {@code []} 而非缺省。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class NotificationListResponse extends BaseResponse {

    @JsonProperty("items")
    private final List<NotificationResponse> items;

    @JsonProperty("next_cursor")
    private final String nextCursor;

    @JsonProperty("has_more")
    private final boolean hasMore;

    private NotificationListResponse(List<NotificationResponse> items, String nextCursor, boolean hasMore) {
        super();
        this.items = items;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public static NotificationListResponse of(List<NotificationResponse> items, String nextCursor, boolean hasMore) {
        return new NotificationListResponse(items, nextCursor, hasMore);
    }

    public List<NotificationResponse> getItems() {
        return items;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
