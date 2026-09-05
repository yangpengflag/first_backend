package com.mooc.backend.messaging.api;

import com.mooc.backend.messaging.dto.ConversationListResponse;
import com.mooc.backend.messaging.dto.ConversationResponse;
import com.mooc.backend.messaging.dto.CreateConversationRequest;
import com.mooc.backend.messaging.exception.MessagingException;
import com.mooc.backend.messaging.service.MessagingService;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 私信会话 HTTP 接口（Task 3.2）。全部端点需 JWT（{@code SecurityConfig.anyRequest().authenticated()}），
 * userId 一律以 JWT 主体推导；{@code UserStatusFilter} 兜底状态门禁（LOCKED→423 /
 * EMAIL_UNVERIFIED→403 / DELETED→401），本类零新增逻辑。错误经
 * {@code MessagingExceptionHandler} 产出统一信封。
 *
 * <p>本类 OpenAPI 注解仅用于文档产出，不改变运行期行为；错误路径由处理器与过滤器链决定，
 * 故 401/403/422/423/429 等在此手工标注（对齐 AuthController 惯例）。
 */
@Tag(name = "私信会话", description = """
        一对一私信的会话发起（幂等）与会话列表（最近消息倒序游标分页，含对方身份、
        最后消息预览与本方未读数）。会话 id 为 UUID v4 不可枚举；非本人会话在
        消息端点一律 404，不泄露存在性。所有错误响应使用统一信封
        {"error":{"code":...,"message":...}}。""")
@RestController
@RequestMapping(value = "/api/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConversationController {

    private final MessagingService messagingService;

    public ConversationController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /**
     * 发起（或幂等复用）会话：新建 201、命中既有 200，两者返回同一会话表示。
     */
    @Operation(summary = "发起会话（幂等）", description = """
            以 recipientId 发起（或复用）与目标用户的一对一会话：同一对用户无论谁发起、
            重复发起多少次，均返回同一会话 id（DB 唯一约束兜底）。新建返回 201，命中既有返回 200。
            recipientId 为自己 → 422 MESSAGE_SELF；不存在 → 404；
            对方 DELETED / LOCKED → 422 USER_UNAVAILABLE（同一错误码与文案，不区分锁定与注销，
            防状态探测）。""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "新建会话，返回会话表示（空会话预览/时间为 null）"),
            @ApiResponse(responseCode = "200", description = "命中既有会话（幂等），返回同一会话表示"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：请求体缺失 recipientId 或非 UUID"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND：recipientId 用户不存在"),
            @ApiResponse(responseCode = "422", description = "MESSAGE_SELF（以自己为 recipientId）或 "
                    + "USER_UNAVAILABLE（对方不可达，不泄露具体状态）"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @PostMapping
    public ResponseEntity<ConversationResponse> create(@Valid @RequestBody CreateConversationRequest request) {
        MessagingService.CreateConversationResult result =
                messagingService.createConversation(currentUserId(), request.recipientId(), Instant.now());
        return ResponseEntity
                .status(result.newlyCreated() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.conversation());
    }

    /**
     * 当前用户会话列表：最近消息时间倒序游标分页（items / next_cursor / has_more），
     * 默认 size=20、上限 50；空会话仅发起者可见（可见性延迟）。
     */
    @Operation(summary = "会话列表", description = """
            按最近消息时间倒序（空会话以创建时间参与全序）游标分页：首传不带 cursor，
            翻页携带上一页 next_cursor。条目含 other_user（id / display_name / avatar_url）、
            last_message_preview（截断 80 字符）、last_message_at 与 unread_count。
            首条消息发出前，会话仅发起者可见。对方注销的会话条目保留，身份降级为 null。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回 items / next_cursor / has_more 信封"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：cursor 非法（解码失败）"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @GetMapping
    public ResponseEntity<ConversationListResponse> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(messagingService.listConversations(currentUserId(), cursor, size));
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
