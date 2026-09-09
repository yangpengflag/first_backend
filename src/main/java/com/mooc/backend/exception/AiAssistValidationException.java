package com.mooc.backend.exception;

/**
 * 写作辅助业务校验异常（change: ai-post-assist，tasks 3.2 / design.md D5 / D5.1）。
 *
 * <p>仅用于「polish 超长」这一类静态 {@code @Size} 无法表达（依赖 kind）的输入拒绝，
 * 由 {@link AiChatExceptionHandler} 转 <b>422</b> {@code VALIDATION_FAILED}。<b>不复用</b>
 * {@link PostException}（会落到 {@code GlobalExceptionHandler} 的 400，破坏与同族 {@code /api/ai/chat}
 * 的一致性）。
 */
public class AiAssistValidationException extends RuntimeException {

    public AiAssistValidationException(String message) {
        super(message);
    }
}
