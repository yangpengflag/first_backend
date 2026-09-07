package com.mooc.backend.exception;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 私信域异常处理器：把 {@link MessagingException} 翻译为统一错误信封
 * {@code {"request_id":...,"error":{"code":...,"message":...}}}。
 *
 * <p>自治于 auth 包的 {@code GlobalExceptionHandler}（不触碰既有错误处理链路）。
 * 必须以最高优先级注册：Spring 对多个 {@code @RestControllerAdvice} 按 {@code @Order}
 * 顺序遍历，一旦某 advice 内存在任意匹配（包括 {@code Exception} 兜底）即被采用；
 * 若本处理器晚于 {@code GlobalExceptionHandler} 被查询，MessagingException
 * 会被其 {@code Exception} 兜底错误地翻译为 500。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class MessagingExceptionHandler {

    @ExceptionHandler(MessagingException.class)
    public ResponseEntity<MessagingErrorResponse> handle(MessagingException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(MessagingErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    /** 与 {@code ErrorResponse} 同构的错误信封（request_id 由 RequestIdFilter 写入 MDC）。 */
    public record MessagingErrorResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("error") ErrorBody error) {

        public record ErrorBody(String code, String message) {
        }

        public static MessagingErrorResponse of(String code, String message) {
            return new MessagingErrorResponse(MDC.get("requestId"), new ErrorBody(code, message));
        }
    }
}
