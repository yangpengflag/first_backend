package com.mooc.backend.exception;

import com.mooc.backend.controller.AiChatController;
import com.mooc.backend.dto.response.ErrorResponse;
import com.mooc.backend.exception.ErrorCode;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * AI 助手模块异常翻译（change: ai-chat-core，tasks 4.1 / 5.2）。
 *
 * <p>照 {@code TravelExceptionHandler} 的分域 advice 模式：{@code @Order} 最高优先级 +
 * {@code assignableTypes} 精确限定 AI 控制器（平铺后各模块 controller 同入 controller 包，
 * 不能用 basePackages 限定，否则 422 校验语义会漂移到其它模块），把三类错误转统一信封
 * （含 request_id），且不影响其它模块的 400 校验语义：
 * <ul>
 *   <li>未装配 {@link AiChatUnavailableException} → <b>503</b> {@code AI_UNAVAILABLE}（spec 降级）</li>
 *   <li>限流 {@link AiChatRateLimitedException} → <b>429</b> {@code RATE_LIMITED}</li>
 *   <li>请求体校验失败 → <b>422</b> {@code VALIDATION_FAILED} + 逐字段 details（spec 多轮对话流式端点）</li>
 * </ul>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AiChatController.class)
public class AiChatExceptionHandler {

    @ExceptionHandler(AiChatUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(AiChatUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(ErrorCode.AI_UNAVAILABLE));
    }

    @ExceptionHandler(AiChatRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(AiChatRateLimitedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(ErrorCode.RATE_LIMITED));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .sorted()
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.getDefaultMessage(), details));
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
