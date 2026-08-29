package com.mooc.backend.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mooc.backend.auth.exception.ErrorCode;

/**
 * 统一错误响应信封：{@code {"error":{"code":...,"message":...,"details":...}}}。
 *
 * <p>{@code code} 为稳定机器码，供前端分支与自动化断言；{@code message} 供人类阅读；
 * {@code details} 仅在参数校验失败时填充（逐项指明违规字段），否则省略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, Object details) {
    }

    public static ErrorResponse of(ErrorCode code) {
        return of(code, code.getDefaultMessage(), null);
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return of(code, message, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, Object details) {
        return new ErrorResponse(new ErrorBody(code.name(), message, details));
    }
}
