package com.mooc.backend.comments.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.comments.exception.CommentException;
import com.mooc.backend.comments.service.CommentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 评论 HTTP 接口。所有端点均需有效 JWT 令牌，{@code userId} 一律以 JWT 主体推导。
 *
 * <p>读端点（列表 / 回复）中 {@code currentUserId()} 仅作鉴权闸门，响应本身不含当前用户态。
 * 错误经 {@code GlobalExceptionHandler}。
 */
@Tag(name = "评论", description = "帖子评论与回复的发布 / 列表 / 删除。所有错误响应使用统一信封。")
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class CommentsController {

    private final CommentService commentService;

    public CommentsController(CommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(summary = "发布评论", description = "需鉴权。顶层评论 parent_comment_id 留空；回复须为本帖某顶层评论 id。")
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentResponse> create(
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest request) {
        UUID userId = currentUserId();
        CommentResponse body = commentService.create(postId, userId, request, Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "顶层评论列表", description = "需鉴权；按创建时间倒序分页，含 reply_count 与作者信息。")
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<Page<CommentResponse>> list(
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(commentService.listTopLevel(postId, page, size, Instant.now()));
    }

    @Operation(summary = "回复列表", description = "需鉴权；按创建时间升序分页。")
    @GetMapping("/comments/{commentId}/replies")
    public ResponseEntity<Page<CommentResponse>> replies(
            @PathVariable UUID commentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(commentService.listReplies(commentId, page, size, Instant.now()));
    }

    @Operation(summary = "删除评论（软删除）", description = "需鉴权且须为作者本人；顶层评论级联软删其回复。")
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID commentId) {
        UUID userId = currentUserId();
        commentService.delete(commentId, userId, Instant.now());
        return ResponseEntity.noContent().build();
    }

    /** 从 SecurityContext 取当前用户标识（JWT 主体为 UUID 字符串）。 */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new CommentException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new CommentException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
