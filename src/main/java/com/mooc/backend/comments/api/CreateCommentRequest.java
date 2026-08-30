package com.mooc.backend.comments.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 发布评论请求。
 *
 * <p>{@code parent_comment_id} 为可空：留空表示顶层评论，否则须为本帖某顶层评论 id
 * （两层模型，不允许嵌套回复）。内容长度上限 {@link #MAX_COMMENT_LENGTH}。
 */
public record CreateCommentRequest(

        @NotBlank(message = "content must not be blank")
        @Size(max = MAX_COMMENT_LENGTH, message = "content too long")
        String content,

        @JsonProperty("parent_comment_id")
        UUID parentCommentId) {

    public static final int MAX_COMMENT_LENGTH = 2000;
}
