package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

/**
 * 未读私信计数响应（导航 inbox badge 数据源，Task 3.2）。
 *
 * <p>计数为当前用户全部会话中「他人发出且未读」的消息总数（走索引）；
 * badge 规则（0 隐藏 / 99+ 封顶）由前端渲染。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class UnreadCountResponse extends BaseResponse {

    @JsonProperty("unread_count")
    private final long unreadCount;

    public UnreadCountResponse(long unreadCount) {
        super();
        this.unreadCount = unreadCount;
    }

    public long getUnreadCount() {
        return unreadCount;
    }
}
