package com.mooc.backend.exception;

/**
 * AI 助手未装配（缺模型凭据或功能开关关闭）时抛出的降级异常（change: ai-chat-core）。
 *
 * <p>语义（spec「模型装配与缺凭据显式降级」）：请求在到达任何外部调用前即失败，
 * 由错误处理转 HTTP 503 + 可读降级消息；日志不含任何模型对话内容或密钥。
 */
public class AiChatUnavailableException extends RuntimeException {

    public AiChatUnavailableException() {
        super("AI chat is not configured on this server.");
    }
}
