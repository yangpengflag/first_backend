package com.mooc.backend.controller;

import com.mooc.backend.config.AiAssistProperties;
import com.mooc.backend.dto.AiAssistRequest;
import com.mooc.backend.dto.response.AiAssistResponse;
import com.mooc.backend.exception.AiChatRateLimitedException;
import com.mooc.backend.exception.ErrorCode;
import com.mooc.backend.exception.PostException;
import com.mooc.backend.service.AiAssistService;
import com.mooc.backend.service.AiAssistSuggestion;
import com.mooc.backend.service.RateLimiter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * 写作辅助 HTTP 接口（change: ai-post-assist，tasks 3.1 / design.md D1 / D5 / D6）。
 *
 * <p><b>必须登录</b>：{@code /api/ai/**} 不在 {@code SecurityConfig.PUBLIC_ENDPOINTS}（仅
 * {@code POST /api/ai/chat} 游客放行），故本端点天然落 {@code anyRequest().authenticated()}，
 * 不在本控制器内做任何游客放行。
 *
 * <p>双维限流（IP + 用户）复用 {@code auth.ratelimit.RateLimiter}（key 为
 * {@code ai-assist|ip|{ip}} / {@code ai-assist|user|{userId}}）；超限 429，不触发模型调用。
 * 校验（{@code @Valid}）+ 委托 service + 构造响应，无业务逻辑；未装配 / 校验失败 / 限流由
 * {@code AiChatExceptionHandler} 统一转信封（503 / 422 / 429）。
 */
@RestController
@RequestMapping(value = "/api/ai/posts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "写作辅助", description = "登录用户的攻略创作 AI 建议（标题 / 标签 / 润色）。")
public class AiAssistController {

    private final AiAssistService aiAssistService;
    private final RateLimiter rateLimiter;
    private final AiAssistProperties properties;

    public AiAssistController(AiAssistService aiAssistService, RateLimiter rateLimiter,
                             AiAssistProperties properties) {
        this.aiAssistService = aiAssistService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Operation(summary = "写作建议", description = "kind=title|tags|polish；需登录，IP+用户双维限流。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JSON：{kind,title?|tags?|content?,request_id}"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED：未登录"),
            @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED：kind 缺失 / 正文过短 / polish 超长"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：限流"),
            @ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE：模型未装配")
    })
    @PostMapping("/assist")
    public ResponseEntity<AiAssistResponse> assist(@Valid @RequestBody AiAssistRequest request,
                                                  HttpServletRequest httpRequest) {
        // IP 维限流（被盗号 / 脚本批量刷的兜底，与用户维互补）。
        if (!rateLimiter.tryAcquire("ai-assist|ip|" + httpRequest.getRemoteAddr(),
                properties.rateLimit().perIpPerMinute(), Duration.ofMinutes(1))) {
            throw new AiChatRateLimitedException();
        }
        // 用户维限流（需在鉴权之后取 userId）。
        UUID userId = currentUserId();
        if (!rateLimiter.tryAcquire("ai-assist|user|" + userId,
                properties.rateLimit().perUserPerMinute(), Duration.ofMinutes(1))) {
            throw new AiChatRateLimitedException();
        }

        AiAssistSuggestion suggestion = aiAssistService.suggest(request.kind(), request.title(), request.content());
        AiAssistResponse body = new AiAssistResponse(
                request.kind().name(), suggestion.title(), suggestion.tags(), suggestion.content());
        return ResponseEntity.ok(body);
    }

    /** 从 SecurityContext 取当前用户标识（JWT 主体为 UUID 字符串），照 PostsController 范式。 */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new PostException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new PostException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
