package com.mooc.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 写作辅助请求（change: ai-post-assist，tasks 3.1 / design.md D1 / D5.1）。
 *
 * <p>请求 DTO 遵循 camelCase 契约（同 {@code ChatRequest} 先例）。{@code kind} 为必填枚举；
 * {@code content} 经 bean validation 表达「过短 / 过长」：最短 20 字符为常量（不做配置项，
 * 避免「配置驱动但只有校验框架能表达」的分裂），最长 12000 覆盖 title/tags 截断上限；
 * polish 的 8000 上限依赖 kind、无法用静态 {@code @Size} 表达，由服务层判并抛
 * {@code AiAssistValidationException}（统一 422）。
 *
 * @param kind    建议类型：title / tags / polish
 * @param title   可选原标题草稿（仅 title 使用）
 * @param content 正文（必填，20–12000 字符）
 */
public record AiAssistRequest(
        @NotNull(message = "kind is required")
        Kind kind,

        String title,

        @NotBlank(message = "content is required")
        @Size(min = 20, max = 12000,
                message = "content must be between 20 and 12000 characters")
        String content) {

    /** 写作辅助动作类型。 */
    public enum Kind {
        title,
        tags,
        polish
    }
}
