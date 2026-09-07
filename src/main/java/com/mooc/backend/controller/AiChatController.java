package com.mooc.backend.controller;

import com.mooc.backend.config.AiChatProperties;
import com.mooc.backend.dto.ChatRequest;
import com.mooc.backend.exception.AiChatRateLimitedException;
import com.mooc.backend.exception.AiChatUnavailableException;
import com.mooc.backend.service.AiChatService;
import com.mooc.backend.service.RateLimiter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 对话 HTTP 接口（change: ai-chat-core，tasks 4.1 / 5.2）。
 *
 * <p><b>游客免登录</b>（SecurityConfig 精确放行 {@code POST /api/ai/chat}）；防滥用由本
 * controller 先行双 key 限流（IP + session，复用 {@code auth.ratelimit.RateLimiter}），
 * 超限 429。随后按装配态降级：未配置模型时 503（JSON 信封，非流）。
 *
 * <p>成功时返回 {@code text/event-stream}（WebMVC {@link SseEmitter} 桥接 service 的
 * {@code Flux}）：增量发 {@code event: delta}，完成发 {@code event: done}，异常发
 * {@code event: error} 并终结——任何情况下流不悬挂（spec「SSE 事件协议与悬挂防护」）。
 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@Tag(name = "AI 行程助手", description = "游客免登录的多轮对话流式端点（SSE）。")
public class AiChatController {

    private static final long EMITTER_TIMEOUT_MS = 120_000L;
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(90);

    private final AiChatService aiChatService;
    private final RateLimiter rateLimiter;
    private final AiChatProperties properties;

    public AiChatController(AiChatService aiChatService, RateLimiter rateLimiter,
                            AiChatProperties properties) {
        this.aiChatService = aiChatService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Operation(summary = "对话", description = "以 SSE 流式返回助手回答；游客免登录（IP/会话双维限流防滥用）。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "text/event-stream：delta…→done"),
            @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED：sessionId/message 非法"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：请求过于频繁"),
            @ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE：模型未配置")
    })
    @PostMapping("/ai/chat")
    public SseEmitter chat(@Valid @RequestBody ChatRequest request,
                           HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire("ai-chat|ip|" + httpRequest.getRemoteAddr(),
                properties.rateLimit().perIpPerMinute(), Duration.ofMinutes(1))) {
            throw new AiChatRateLimitedException();
        }
        if (!rateLimiter.tryAcquire("ai-chat|session|" + request.sessionId(),
                properties.rateLimit().perSessionPerMinute(), Duration.ofMinutes(1))) {
            throw new AiChatRateLimitedException();
        }
        if (!aiChatService.configured()) {
            throw new AiChatUnavailableException();
        }
        return streamToEmitter(request.sessionId(), request.message());
    }

    private SseEmitter streamToEmitter(String sessionId, String message) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        AtomicBoolean finished = new AtomicBoolean(false);
        Disposable[] subscription = new Disposable[1];

        emitter.onCompletion(() -> {
            if (subscription[0] != null) {
                subscription[0].dispose();
            }
        });
        emitter.onTimeout(() -> {
            if (subscription[0] != null) {
                subscription[0].dispose();
            }
        });

        subscription[0] = aiChatService.stream(sessionId, message)
                .timeout(UPSTREAM_TIMEOUT)
                .subscribe(
                        token -> send(emitter, finished, "delta", Map.of("content", token)),
                        error -> {
                            send(emitter, finished, "error",
                                    Map.of("code", "CHAT_STREAM_ERROR",
                                            "message", "The AI request failed. Please try again."));
                            finish(emitter, finished);
                        },
                        () -> {
                            send(emitter, finished, "done", Map.of());
                            finish(emitter, finished);
                        });
        return emitter;
    }

    private void send(SseEmitter emitter, AtomicBoolean finished, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            // 客户端已断开：终止会话（幂等），不再写入
            finish(emitter, finished);
        }
    }

    private void finish(SseEmitter emitter, AtomicBoolean finished) {
        if (finished.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 已 complete / 超时，幂等忽略
            }
        }
    }
}
