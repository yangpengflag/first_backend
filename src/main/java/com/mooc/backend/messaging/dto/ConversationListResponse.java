package com.mooc.backend.messaging.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.List;

/**
 * 会话列表统一分页信封（对齐 {@code NotificationListResponse} / {@code PostListResponse}
 * 既有惯例，Task 3.2）。
 *
 * <p>始终返回 {@code items} / {@code next_cursor} / {@code has_more}；
 * 游标模式不输出 page / size / total。按最近消息时间倒序（空会话以创建时间参与全序），
 * 空列表输出 {@code []} 而非缺省。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ConversationListResponse extends BaseResponse {

    @JsonProperty("items")
    private final List<ConversationResponse> items;

    @JsonProperty("next_cursor")
    private final String nextCursor;

    @JsonProperty("has_more")
    private final boolean hasMore;

    private ConversationListResponse(List<ConversationResponse> items, String nextCursor, boolean hasMore) {
        super();
        this.items = items;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public static ConversationListResponse of(List<ConversationResponse> items, String nextCursor, boolean hasMore) {
        return new ConversationListResponse(items, nextCursor, hasMore);
    }

    public List<ConversationResponse> getItems() {
        return items;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
