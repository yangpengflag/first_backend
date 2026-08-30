package com.mooc.backend.bookmarks.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.bookmarks.exception.BookmarkException;
import com.mooc.backend.bookmarks.service.BookmarkService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 收藏 HTTP 接口。所有端点均需 JWT 鉴权；{@code userId} 由 JWT 主体推导。
 */
@Tag(name = "收藏", description = "帖子收藏的切换与列表。所有错误响应使用统一信封。")
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class BookmarksController {

    private final BookmarkService bookmarkService;

    public BookmarksController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @Operation(summary = "切换收藏", description = "需鉴权。已收藏则取消，未收藏则收藏。")
    @PostMapping("/posts/{postId}/bookmark")
    public ResponseEntity<BookmarkResponse> toggle(@PathVariable UUID postId) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(bookmarkService.toggle(postId, userId, Instant.now()));
    }

    @Operation(summary = "收藏状态查询", description = "需鉴权。精确返回当前用户是否已收藏该帖；帖子不存在返回 404。")
    @GetMapping("/posts/{postId}/bookmark")
    public ResponseEntity<BookmarkStatusResponse> status(@PathVariable UUID postId) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(BookmarkStatusResponse.from(postId, bookmarkService.isBookmarked(postId, userId)));
    }

    @Operation(summary = "我的收藏列表", description = "需鉴权；全量返回（失效帖子以 available=false 占位），按收藏时间倒序。")
    @GetMapping("/bookmarks")
    public ResponseEntity<Page<BookmarkSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(bookmarkService.listBookmarks(userId, page, size, Instant.now()));
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new BookmarkException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new BookmarkException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
