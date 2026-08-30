package com.mooc.backend.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.dto.response.BaseResponse;

/**
 * 统一错误响应信封：{@code {"request_id":..., "error":{"code":...,"message":...,"details":...}}}。
 *
 * <p>{@code code} 为稳定机器码，供前端分支与自动化断言；{@code message} 供人类阅读；
 * {@code details} 仅在参数校验失败时填充（逐项指明违规字段），否则省略。
 * 继承 {@code BaseResponse} 后，所有错误响应亦携带 request_id（顶层，与成功响应一致）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse extends BaseResponse {

    @JsonProperty("error") private final ErrorBody error;

    public ErrorResponse(ErrorBody error) {
        super();
        this.error = error;
    }

    public ErrorBody getError() {
        return error;
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

    public record ErrorBody(String code, String message, Object details) {
    }
}
