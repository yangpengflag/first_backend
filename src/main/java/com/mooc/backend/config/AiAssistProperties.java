package com.mooc.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 写作辅助配置（change: ai-post-assist，tasks 1.1 / design.md D5 / D6）。
 * 前缀 {@code app.ai-assist}，经 {@code @EnableConfigurationProperties} 注册于 {@link AiChatConfig}。
 *
 * <p>复用 {@code app.ai-chat.enabled} 作为站点 AI 总开关（不新增 enabled 哨兵）；本配置只承载
 * 与「辅助」相关的输入上限、限流与超时。超时仅作用于同步 {@code RestClient}（assist 走同步
 * 调用），不影响 SSE 对话（对话上界由 controller 既有的 90s/120s 承担）。
 *
 * @param maxInputChars   title / tags 的 content 截断上限（超出只损失建议质量，不损失数据）
 * @param polishMaxChars  polish 的 content 拒绝阈值（超出拒绝、绝不截断，防静默删稿）
 * @param titleMaxChars   标题裁剪上限，对齐 Post.title
 * @param rateLimit       双维限流阈值（IP + 已登录用户）
 * @param timeout         同步 RestClient 连接 / 读取超时
 */
@ConfigurationProperties(prefix = "app.ai-assist")
public record AiAssistProperties(
        int maxInputChars,
        int polishMaxChars,
        int titleMaxChars,
        RateLimit rateLimit,
        Timeout timeout) {

    /** 双维限流（IP 维 + 用户维）。复用 auth RateLimiter 的滑动窗口。 */
    public record RateLimit(int perIpPerMinute, int perUserPerMinute) {
    }

    /** 同步 RestClient 超时（仅约束 assist 的同步调用，不影响 SSE 对话）。 */
    public record Timeout(int connectMs, int readMs) {
    }
}
