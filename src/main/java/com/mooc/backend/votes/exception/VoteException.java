package com.mooc.backend.votes.exception;

import com.mooc.backend.auth.exception.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 投票域业务异常，携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 翻译为统一错误信封。
 */
public class VoteException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final Object details;

    public VoteException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public VoteException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public VoteException(ErrorCode errorCode, String message, Object details) {
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
