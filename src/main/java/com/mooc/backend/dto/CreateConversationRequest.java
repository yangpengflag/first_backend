package com.mooc.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 发起会话请求（请求体 camelCase record，惯例对齐 CreateCommentRequest）。
 */
public record CreateConversationRequest(@NotNull UUID recipientId) {
}
