package com.mooc.backend.controller;

import com.mooc.backend.service.RateLimiter;
import com.mooc.backend.config.MessagingProperties;
import com.mooc.backend.dto.response.MessageListResponse;
import com.mooc.backend.dto.response.MessageResponse;
import com.mooc.backend.dto.SendMessageRequest;
import com.mooc.backend.dto.response.UnreadCountResponse;
import com.mooc.backend.exception.MessagingException;
import com.mooc.backend.service.MessagingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 私信消息 HTTP 接口（Task 2.3 / 3.2）。全部端点需 JWT，userId 由 JWT 主体推导；
 * {@code UserStatusFilter} 兜底状态门禁，本类零新增状态逻辑。错误经
 * {@code MessagingExceptionHandler} 产出统一信封。
 *
 * <p>限流：发送消息在鉴权后按用户维度限流（复用 {@code auth.ratelimit.RateLimiter}，
 * 阈值 {@code messaging.rate-limit.send-per-user-per-minute}）。不得在
 * {@code RateLimitFilter} 内做用户维度限流——该过滤器位于 JwtAuthFilter 之前，主体尚未解析。
 *
 * <p>打开会话即已读：读取历史（{@code GET /conversations/{id}/messages}）在服务层
 * 同事务批量置读对方发出的未读消息（design.md「随历史请求触发」选项），幂等。
 */
@Tag(name = "私信消息", description = """
        一对一私信的消息收发与历史：发送（纯文本 trim 后非空 ≤2000 字符、非成员 404、
        按用户限流 429）、历史（created_at 正序呈现、倒查反转 + before 游标向上翻页、
        打开会话即已读）、未读计数（badge 数据源）。所有错误响应使用统一信封。""")
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class MessageController {

    private final MessagingService messagingService;
    private final RateLimiter rateLimiter;
    private final MessagingProperties messagingProperties;

    public MessageController(MessagingService messagingService,
                             RateLimiter rateLimiter,
                             MessagingProperties messagingProperties) {
        this.messagingService = messagingService;
        this.rateLimiter = rateLimiter;
        this.messagingProperties = messagingProperties;
    }

    /** 发送消息：先限流（用户维度）再入服务；成功 201。 */
    @Operation(summary = "发送消息", description = """
            仅会话两名成员可调用，非成员与不存在的会话共用 404（防枚举）。
            内容为纯文本、trim 后非空、≤2000 字符，HTML 按字面文本处理。
            成功落库并同事务刷新会话 last_message_*。按用户维度限流，超限 429。""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "发送成功，返回消息对象（发送者自己的消息 read_at 为 null）"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：内容空白或超长"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "404", description = "CONVERSATION_NOT_FOUND：不存在或非成员（同型，防枚举）"),
            @ApiResponse(responseCode = "422", description = "USER_UNAVAILABLE：对方不可达（不泄露具体状态）"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：发送过于频繁")
    })
    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<MessageResponse> send(@PathVariable UUID id,
                                                @Valid @RequestBody SendMessageRequest request) {
        UUID userId = currentUserId();
        String key = "message|user|" + userId;
        if (!rateLimiter.tryAcquire(key, messagingProperties.getSendPerUserPerMinute(), Duration.ofMinutes(1))) {
            throw MessagingException.rateLimited();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messagingService.sendMessage(id, userId, request.content(), Instant.now()));
    }

    /** 消息历史：created_at 正序呈现（倒查反转）；打开会话即已读。 */
    @Operation(summary = "消息历史（打开会话即已读）", description = """
            返回会话消息，items 恒为旧→新（对话阅读方向）；首屏为最新 N 条倒查后反转。
            has_more_earlier 标示是否还有更早消息；向上翻页携带 next_before 作为 before 游标续拉。
            读取即已读：对方发出的未读消息在本请求内批量标记已读（幂等，badge 相应减少）。
            非成员与不存在的会话共用 404。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回 items / next_before / has_more_earlier 信封"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：before 游标非法（解码失败）"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "404", description = "CONVERSATION_NOT_FOUND：不存在或非成员（同型，防枚举）"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<MessageListResponse> history(@PathVariable UUID id,
                                                       @RequestParam(required = false) String before,
                                                       @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(
                messagingService.listMessages(id, currentUserId(), before, size, Instant.now()));
    }

    /** 未读私信总数（badge 数据源）。 */
    @Operation(summary = "未读私信计数", description = """
            返回当前用户在全部会话中、他人发出且未读的消息总数（走索引）；
            前端 badge 0 隐藏 / 99+ 封顶，与通知铃铛并列独立展示。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回 unread_count"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @GetMapping("/messages/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount() {
        return ResponseEntity.ok(new UnreadCountResponse(messagingService.getUnreadCount(currentUserId())));
    }

    /** 从 SecurityContext 取当前用户标识（JWT 主体为 UUID 字符串）。 */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw MessagingException.unauthenticated(); // 过滤链已保证 authenticated，此处仅防御性兜底
        }
        return UUID.fromString(authentication.getName());
    }
}
