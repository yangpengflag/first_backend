package com.mooc.backend.travel.exception;

import com.mooc.backend.auth.exception.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 旅行模块业务异常（change: add-travel-services，task 5.2）。
 *
 * <p>照 {@code PlacesException} / {@code PostException} 的既有形状：携带 {@link ErrorCode}
 * （HTTP 状态码 + 稳定机器码），由模块内的 {@code TravelExceptionHandler} 翻成统一错误信封。
 * 当前唯一用途：weather 端点的未收录 slug → 404 {@code CITY_NOT_FOUND}——复用既有错误码而非
 * 新造，前端与 openapi 快照都不用认识第二个名字。
 */
public class TravelException extends RuntimeException {

    private final ErrorCode errorCode;

    public TravelException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return errorCode.getStatus();
    }
}
