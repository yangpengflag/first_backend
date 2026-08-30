package com.mooc.backend.comments.exception;

import com.mooc.backend.auth.exception.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 评论域业务异常，携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 翻译为统一错误信封。
 *
 * <p>与 {@code PostException} 同构，但归属 comments 模块，避免跨模块继承 auth 异常。
 */
public class CommentException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final Object details;

    public CommentException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public CommentException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public CommentException(ErrorCode errorCode, String message, Object details) {
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
