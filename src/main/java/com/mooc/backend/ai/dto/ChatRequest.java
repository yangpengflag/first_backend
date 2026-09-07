package com.mooc.backend.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 对话请求（change: ai-chat-core）。**请求 DTO 遵循 camelCase 契约**（同 RegisterRequest 等先例）。
 *
 * @param sessionId 会话标识（客户端生成的 UUID v4）
 * @param message   本轮用户消息（trim 后非空、≤ 4000 字符）
 */
public record ChatRequest(
        @NotBlank(message = "sessionId is required")
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "sessionId must be a UUID")
        String sessionId,

        @NotBlank(message = "message is required")
        @Size(max = 4000, message = "message must be at most 4000 characters")
        String message) {
}
