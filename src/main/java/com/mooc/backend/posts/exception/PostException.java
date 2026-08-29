package com.mooc.backend.posts.exception;

import com.mooc.backend.auth.exception.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 帖子域业务异常，携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 翻译为统一错误信封。
 *
 * <p>与 {@code AuthException} 同构，但归属 posts 模块，避免跨模块继承 auth 异常。
 */
public class PostException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final Object details;

    public PostException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public PostException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public PostException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.status = errorCode.getStatus();
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Object getDetails() {
        return details;
    }
}
