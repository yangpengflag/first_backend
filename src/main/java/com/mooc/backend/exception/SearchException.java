package com.mooc.backend.exception;

/**
 * 搜索域业务异常（change: ai-semantic-search），携带 {@link ErrorCode}，
 * 由 {@code GlobalExceptionHandler} 翻译为统一错误信封：q 校验失败 → 400
 * {@code VALIDATION_FAILED}；限流 → 429 {@code RATE_LIMITED}。
 */
public class SearchException extends RuntimeException {

    private final ErrorCode errorCode;

    public SearchException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public SearchException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
