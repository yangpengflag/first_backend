package com.mooc.backend.controller;

import com.mooc.backend.service.NotificationService;
import com.mooc.backend.dto.response.NotificationListResponse;
import com.mooc.backend.dto.response.NotificationResponse;
import com.mooc.backend.dto.response.UnreadCountResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知 HTTP 接口（Task 3.2）。全部端点需 JWT（{@code SecurityConfig.anyRequest().authenticated()}），
 * recipient 一律以 JWT 主体推导；{@code UserStatusFilter} 兜底状态门禁（LOCKED→423 /
 * EMAIL_UNVERIFIED→403 / DELETED→401），本类零新增逻辑。错误经
 * {@code NotificationExceptionHandler} 产出统一信封。
 *
 * <p>本类 OpenAPI 注解仅用于文档产出，不改变运行期行为；错误路径由处理器与过滤器链决定，
 * 故 401/403/423 与特定错误码在此手工标注（对齐 AuthController 惯例）。
 */
@Tag(name = "通知", description = """
        站内互动通知：列表（last_interacted_at 倒序游标分页）、未读计数、单条已读（幂等）、
        全部已读。仅返回当前用户（recipient）的通知；操作他人通知一律 404，不泄露存在性。
        所有错误响应使用统一信封 {"error":{"code":...,"message":...}}。""")
@RestController
@RequestMapping(value = "/api/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationsController {

    private final NotificationService notificationService;

    public NotificationsController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 通知列表：按 {@code last_interacted_at} 倒序游标分页，信封对齐 posts 列表惯例
     * （items / next_cursor / has_more，不输出 total）；默认 size=20、上限 50。
     */
    @Operation(summary = "通知列表", description = """
            仅返回当前用户的通知，按最后互动时间倒序。cursor 模式：首传不带 cursor，
            翻页携带上一页 next_cursor；unread_only=true 仅返回未读。
            actor 已注销（软删）时条目照常返回，display_name / avatar_url 为 null，由前端降级展示。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回 items / next_cursor / has_more 信封"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：cursor 非法（解码失败）"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @GetMapping
    public ResponseEntity<NotificationListResponse> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "unread_only", required = false) Boolean unreadOnly) {
        NotificationListResponse body =
                notificationService.listNotifications(currentUserId(), cursor, size, unreadOnly);
        return ResponseEntity.ok(body);
    }

    /** 未读通知总数（badge 数据源，count 走索引）。 */
    @Operation(summary = "未读通知计数", description = "返回当前用户未读通知总数；前端 badge 0 隐藏 / 99+ 封顶。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回 unread_count"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount() {
        return ResponseEntity.ok(new UnreadCountResponse(notificationService.unreadCount(currentUserId())));
    }

    /** 标记单条已读（幂等）：非本人或不存在一律 404（同型错误，防枚举）。 */
    @Operation(summary = "标记单条已读", description = """
            为目标通知写入首次 read_at；重复调用返回 200 且无副作用（不覆盖首次已读时间）。
            非本人通知与不存在的通知共用 404，不泄露他人通知 id 的存在性。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回已读后的通知（已读再调用返回原 read_at）"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "404", description = "NOTIFICATION_NOT_FOUND：不存在或非本人（同型，防枚举）"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markRead(@PathVariable UUID id) {
        return ResponseEntity.ok(notificationService.markRead(id, currentUserId(), Instant.now()));
    }

    /** 全部标记已读：批量写当前用户未读行的 read_at → 204。 */
    @Operation(summary = "全部标记已读", description = """
            将当前用户全部未读通知写入 read_at（幂等：既有已读行不覆盖），
            此后新互动仍正常产生未读通知。""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "批量置读成功，无响应体"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead(currentUserId(), Instant.now());
        return ResponseEntity.noContent().build();
    }

    /** 从 SecurityContext 取当前用户标识（JWT 主体为 UUID 字符串）。 */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null; // 过滤链已保证 authenticated，此处仅防御性兜底
        }
        return UUID.fromString(authentication.getName());
    }
}
