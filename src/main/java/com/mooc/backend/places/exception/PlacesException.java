package com.mooc.backend.places.exception;

import com.mooc.backend.auth.exception.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 目的地域（城市 / 景点）业务异常，携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 翻译为统一错误信封。
 *
 * <p>与 {@code PostException} 同构，归属 places 模块，避免跨模块继承 posts 异常。
 */
public class PlacesException extends RuntimeException {

    private final ErrorCode errorCode;

    public PlacesException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public PlacesException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return errorCode.getStatus();
    }
}
