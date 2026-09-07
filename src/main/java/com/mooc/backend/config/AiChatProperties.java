package com.mooc.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 助手配置（change: ai-chat-core）。前缀 {@code app.ai-chat}，经
 * {@code @EnableConfigurationProperties} 注册（照 Travel/Messaging/Auth 各模块惯例，
 * 不引入全仓 {@code @ConfigurationPropertiesScan}）。
 *
 * @param enabled      功能开关：false 时对话端点恒降级（kill switch），装配与否还取决于 api-key
 * @param contextWindow 上下文窗口：每次生成载入会话最近 N 条消息（含本轮 user 消息）
 * @param retentionDays 匿名会话保留天数：超过则清理任务删除会话及消息
 * @param rateLimit    游客免登录端点防滥用阈值（IP 与会话双维度，复用 auth RateLimiter）
 */
@ConfigurationProperties(prefix = "app.ai-chat")
public record AiChatProperties(
        boolean enabled,
        int contextWindow,
        int retentionDays,
        RateLimit rateLimit) {

    public record RateLimit(int perIpPerMinute, int perSessionPerMinute) {
    }
}
