package com.mooc.backend.bookmarks.exception;

import com.mooc.backend.auth.exception.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 收藏域业务异常，携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 翻译为统一错误信封。
 */
public class BookmarkException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final Object details;

    public BookmarkException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public BookmarkException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BookmarkException(ErrorCode errorCode, String message, Object details) {
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
