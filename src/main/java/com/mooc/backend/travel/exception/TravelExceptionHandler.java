package com.mooc.backend.travel.exception;

import com.mooc.backend.auth.api.ErrorResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 旅行模块异常翻译（change: add-travel-services，task 5.2）。
 *
 * <p>照 {@code MessagingExceptionHandler} / {@code NotificationExceptionHandler} 的分域 advice
 * 模式，而非往 {@code GlobalExceptionHandler} 里加分支：各模块自持异常类型，全局处理器保持
 * 与具体模块解耦。输出统一错误信封 {@code {"error":{"code":...,"message":...}}}（含 request_id）。
 *
 * <p><b>{@code @Order} 必须最高优先级</b>：Spring 对多个 advice 按 {@code @Order} 顺序遍历，
 * 一旦某 advice 内存在任意匹配（包括 {@code Exception} 兜底）即被采用——不提前的话
 * {@code TravelException} 会被 {@code GlobalExceptionHandler} 的兜底错误地翻译成 500。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class TravelExceptionHandler {

    @ExceptionHandler(TravelException.class)
    public ResponseEntity<ErrorResponse> handleTravelException(TravelException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), null));
    }
}
