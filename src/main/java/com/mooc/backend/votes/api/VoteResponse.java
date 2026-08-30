package com.mooc.backend.votes.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.Set;
import java.util.UUID;

/**
 * 投票操作响应（snake_case 白名单，对齐 backend-conventions）。
 *
 * <p>{@code user_vote} 为当前用户投票态：{@code "UP"} / {@code "DOWN"} / {@code null}（已取消或未投）。
 * 继承 {@code BaseResponse} 自带 request_id。绝不暴露 {@code deleted_at} 等审计字段。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class VoteResponse extends BaseResponse {

    @JsonProperty("post_id") private final UUID postId;
    @JsonProperty("user_vote") private final String userVote;

    public VoteResponse(UUID postId, String userVote) {
        super();
        this.postId = postId;
        this.userVote = userVote;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of("post_id", "user_vote", "request_id");

    public static VoteResponse from(UUID postId, String userVote) {
        return new VoteResponse(postId, userVote);
    }

    public UUID getPostId() {
        return postId;
    }

    public String getUserVote() {
        return userVote;
    }
}
