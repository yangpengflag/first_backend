package com.mooc.backend.places.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.comments.api.CreateCommentRequest;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.service.SpotCommentService;

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
 * 景点评论 HTTP 接口（镜像 comments.CommentsController，postId → spotSlug）。
 *
 * <p>所有端点均需有效 JWT 令牌，{@code userId} 一律以 JWT 主体推导。回复端点独立为
 * {@code /api/spot-comments/{id}/replies}（景点评论存于独立 {@code spot_comments} 表，
 * 复用帖子评论的 {@code /api/comments/{id}/replies} 会 404）。错误经 {@code GlobalExceptionHandler}。
 */
@Tag(name = "景点评论", description = "景点评论与回复的发布 / 列表 / 删除。所有错误响应使用统一信封。")
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class SpotCommentsController {

    private final SpotCommentService spotCommentService;

    public SpotCommentsController(SpotCommentService spotCommentService) {
        this.spotCommentService = spotCommentService;
    }

    @Operation(summary = "发布景点评论", description = "需鉴权。顶层评论 parent_comment_id 留空；回复须为本景点某顶层评论 id。")
    @PostMapping("/spots/{slug}/comments")
    public ResponseEntity<SpotCommentResponse> create(
            @PathVariable String slug,
            @Valid @RequestBody CreateCommentRequest request) {
        UUID userId = currentUserId();
        SpotCommentResponse body = spotCommentService.create(slug, userId, request, Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "景点顶层评论列表", description = "需鉴权；按创建时间倒序分页，含 reply_count 与作者信息。")
    @GetMapping("/spots/{slug}/comments")
    public ResponseEntity<Page<SpotCommentResponse>> list(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(spotCommentService.listTopLevel(slug, page, size, Instant.now()));
    }

    @Operation(summary = "景点评论回复列表", description = "需鉴权；按创建时间升序分页。")
    @GetMapping("/spot-comments/{commentId}/replies")
    public ResponseEntity<Page<SpotCommentResponse>> replies(
            @PathVariable UUID commentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(spotCommentService.listReplies(commentId, page, size, Instant.now()));
    }

    @Operation(summary = "删除景点评论（软删除）", description = "需鉴权；作者本人或 ADMIN 可删；顶层评论级联软删其回复。")
    @DeleteMapping("/spot-comments/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID commentId) {
        UUID userId = currentUserId();
        spotCommentService.delete(commentId, userId, Instant.now());
        return ResponseEntity.noContent().build();
    }

    /** 从 SecurityContext 取当前用户标识（JWT 主体为 UUID 字符串）。 */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new PlacesException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new PlacesException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
