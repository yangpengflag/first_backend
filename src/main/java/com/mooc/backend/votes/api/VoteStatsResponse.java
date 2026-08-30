package com.mooc.backend.votes.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.Set;
import java.util.UUID;

/**
 * 投票统计响应（snake_case 白名单）。
 *
 * <p>{@code user_vote} 为当前令牌用户的投票态（未投为 {@code null}）；端点需鉴权故必有值或 null。
 * 继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class VoteStatsResponse extends BaseResponse {

    @JsonProperty("post_id") private final UUID postId;
    @JsonProperty("up_count") private final long upCount;
    @JsonProperty("down_count") private final long downCount;
    @JsonProperty("user_vote") private final String userVote;

    public VoteStatsResponse(UUID postId, long upCount, long downCount, String userVote) {
        super();
        this.postId = postId;
        this.upCount = upCount;
        this.downCount = downCount;
        this.userVote = userVote;
    }

    public static final Set<String> WHITELISTED_FIELDS =
            Set.of("post_id", "up_count", "down_count", "user_vote", "request_id");

    public static VoteStatsResponse from(UUID postId, long upCount, long downCount, String userVote) {
        return new VoteStatsResponse(postId, upCount, downCount, userVote);
    }

    public UUID getPostId() {
        return postId;
    }

    public long getUpCount() {
        return upCount;
    }

    public long getDownCount() {
        return downCount;
    }

    public String getUserVote() {
        return userVote;
    }
}
