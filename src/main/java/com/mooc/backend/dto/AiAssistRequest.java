package com.mooc.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 写作辅助请求（change: ai-post-assist，tasks 3.1 / design.md D1 / D5.1）。
 *
 * <p>请求 DTO 遵循 camelCase 契约（同 {@code ChatRequest} 先例）。{@code kind} 为必填枚举；
 * {@code content} 经 bean validation 表达「过短 / 过长」：最短长度按 kind 分档（tags≥1 / title≥5 /
 * polish≥20，均为常量不做配置项，避免「配置驱动但只有校验框架能表达」的分裂）；DTO 仅保非空 + 上限
 * 12000，随 kind 变化的最短长度由 service 层判定（见 design.md D1）；polish 的 8000 上限依赖 kind、
 * 无法用静态 {@code @Size} 表达，由服务层判并抛
 * {@code AiAssistValidationException}（统一 422）。
 *
 * @param kind    建议类型：title / tags / polish
 * @param title   可选原标题草稿（仅 title 使用）
 * @param content 正文（必填，非空且 ≤12000 字符；随 kind 的最短长度由 service 层判定）
 */
public record AiAssistRequest(
        @NotNull(message = "kind is required")
        Kind kind,

        String title,

        @NotBlank(message = "content is required")
        @Size(min = 1, max = 12000,
                message = "content must be between 1 and 12000 characters")
        String content) {

    /** 写作辅助动作类型。 */
    public enum Kind {
        title,
        tags,
        polish
    }
}
