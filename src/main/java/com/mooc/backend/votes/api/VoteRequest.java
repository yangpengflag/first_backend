package com.mooc.backend.votes.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.votes.domain.VoteType;

import jakarta.validation.constraints.NotNull;

/**
 * 投票请求。{@code vote_type} 枚举取值 {@code UP} / {@code DOWN}；非法值由 Jackson 反序列化失败转为 400。
 * 请求体字段采用 snake_case（与 {@code CreateCommentRequest.parent_comment_id} 等本特性请求一致）。
 */
public record VoteRequest(

        @NotNull(message = "vote_type must not be null")
        @JsonProperty("vote_type")
        VoteType voteType) {
}
