package com.mooc.backend.config;

/**
 * 混合搜索配置（change: ai-semantic-search，design.md D5/D6）。前缀 {@code app.search}，
 * 默认值写在 application.yml（本 record 不设默认，与 AiRagProperties 同约定）。
 *
 * @param vectorTopK        向量腿检索条数（每查询一次 DashScope embedding，靠该值与限流收口成本）
 * @param rateLimitEnabled  IP 维度限流开关（本地开发可关闭，零摩擦）
 * @param rateLimitPerMinute 单 IP 每分钟允许的搜索次数
 */
public record SearchProperties(
        int vectorTopK,
        boolean rateLimitEnabled,
        int rateLimitPerMinute) {
}
