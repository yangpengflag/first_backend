package com.mooc.backend.ai.exception;

/**
 * AI 对话限流异常（change: ai-chat-core）：IP 或会话维度超限时抛出，转 429 统一信封。
 */
public class AiChatRateLimitedException extends RuntimeException {

    public AiChatRateLimitedException() {
        super("Too many chat requests. Please try again later.");
    }
}
