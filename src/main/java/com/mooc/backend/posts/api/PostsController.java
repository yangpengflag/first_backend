package com.mooc.backend.posts.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.posts.exception.PostException;
import com.mooc.backend.posts.service.PostService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import com.mooc.backend.posts.api.PostListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.time.Instant;
import java.util.UUID;

/**
 * 帖子 HTTP 接口。
 *
 * <p>公开读端点（列表 / 详情）由 {@code SecurityConfig} 放行；写操作与 {@code /me} 需有效令牌，
 * 且 {@code authorId} 一律以 JWT 主体推导，忽略请求体传入值。错误经 {@code GlobalExceptionHandler}。
 */
@Tag(name = "帖子", description = """
        旅行攻略（Story）的发布 / 编辑 / 公开列表 / 详情 / 我的帖子。
        所有错误响应使用统一信封 {"error":{"code":...,"message":...}}。""")
@RestController
@RequestMapping(value = "/api/posts", produces = MediaType.APPLICATION_JSON_VALUE)
public class PostsController {

    private final PostService postService;

    public PostsController(PostService postService) {
        this.postService = postService;
    }

    @Operation(summary = "创建帖子", description = "authorId 取自令牌主体；status 缺省 DRAFT，可直传 PUBLISHED 发布。")
    @PostMapping
    public ResponseEntity<PostResponse> create(@Valid @RequestBody CreatePostRequest request) {
        UUID authorId = currentUserId();
        PostResponse body = postService.create(authorId, request, Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "公开列表", description = "仅返回 PUBLISHED；支持 sort=latest（cursor 翻页）/ top / most_commented（offset 翻页），每项含作者展示信息与互动统计。")
    @GetMapping
    public ResponseEntity<PostListResponse> list(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String spotId) {
        if (cityId != null || spotId != null) {
            return ResponseEntity.ok(postService.listByLocation(sort, page, size, cityId, spotId, Instant.now()));
        }
        return ResponseEntity.ok(postService.listPublished(sort, cursor, page, size, Instant.now()));
    }

    @Operation(summary = "我的帖子", description = "需鉴权，返回当前用户全部状态（含 DRAFT）的帖子，并带互动统计与排序 / 分页。")
    @GetMapping("/me")
    public ResponseEntity<PostListResponse> listMine(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        UUID authorId = currentUserId();
        return ResponseEntity.ok(postService.listMine(authorId, sort, cursor, page, size, Instant.now()));
    }

    @Operation(summary = "帖子详情", description = "仅返回 PUBLISHED；草稿 / 已软删返回 404。")
    @GetMapping("/{id}")
    public ResponseEntity<PostResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(postService.getPublished(id, Instant.now()));
    }

    @Operation(summary = "编辑帖子", description = "仅作者本人可编辑；可补丁式更新，含 DRAFT→PUBLISHED 发布。")
    @PutMapping("/{id}")
    public ResponseEntity<PostResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePostRequest request) {
        UUID authorId = currentUserId();
        return ResponseEntity.ok(postService.update(id, authorId, request, Instant.now()));
    }

    @Operation(summary = "删除帖子（软删除）", description = "仅作者本人可删；软删除保留行，自动从所有列表 / 详情消失。")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID authorId = currentUserId();
        postService.delete(id, authorId, Instant.now());
        return ResponseEntity.noContent().build();
    }

    /** 从 SecurityContext 取当前用户标识（JWT 主体为 UUID 字符串）。 */
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
