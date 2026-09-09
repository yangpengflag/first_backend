package com.mooc.backend.service;

import java.util.List;

/**
 * 写作辅助结果（change: ai-post-assist，tasks 2.2）。按 {@code kind} 仅填充对应字段，
 * 其余为 {@code null}（响应层以 {@code NON_NULL} 省略，表达「该 kind 无结果」）。
 *
 * @param title   标题建议（kind=title 时填充，空结果时 null）
 * @param tags    标签建议（kind=tags 时填充，空结果时 null）
 * @param content 润色后正文（kind=polish 时填充，空结果时 null）
 */
public record AiAssistSuggestion(String title, List<String> tags, String content) {
}
