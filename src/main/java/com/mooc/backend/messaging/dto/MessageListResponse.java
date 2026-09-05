package com.mooc.backend.messaging.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.List;

/**
 * 消息历史信封（Task 3.2）。
 *
 * <p>{@code items} 恒为旧→新（对话阅读方向，倒查反转后输出）；
 * {@code has_more_earlier} 标示是否还有更早消息（以「取满」判断）；
 * {@code next_before} 为向上翻页游标——传入下一请求的 {@code before} 参数续拉更早消息，
 * 无更早消息时为 {@code null}。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class MessageListResponse extends BaseResponse {

    @JsonProperty("items")
    private final List<MessageResponse> items;

    @JsonProperty("next_before")
    private final String nextBefore;

    @JsonProperty("has_more_earlier")
    private final boolean hasMoreEarlier;

    private MessageListResponse(List<MessageResponse> items, String nextBefore, boolean hasMoreEarlier) {
        super();
        this.items = items;
        this.nextBefore = nextBefore;
        this.hasMoreEarlier = hasMoreEarlier;
    }

    public static MessageListResponse of(List<MessageResponse> items, String nextBefore, boolean hasMoreEarlier) {
        return new MessageListResponse(items, nextBefore, hasMoreEarlier);
    }

    public List<MessageResponse> getItems() {
        return items;
    }

    public String getNextBefore() {
        return nextBefore;
    }

    public boolean isHasMoreEarlier() {
        return hasMoreEarlier;
    }
}
