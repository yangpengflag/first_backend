package com.mooc.backend.places.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.service.SpotBookmarkService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
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
 * 景点收藏 HTTP 接口（仅认证用户）。
 *
 * <p>{@code userId} 由 SecurityContext 推导（JWT 主体为 UUID 字符串）。
 * 切换 / 状态 / 我的列表均需鉴权；景点不存在统一 404（SPOT_NOT_FOUND）。
 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "景点收藏", description = "景点收藏切换、状态查询与我的收藏列表（均需鉴权）。")
public class SpotBookmarksController {

    private final SpotBookmarkService spotBookmarkService;

    public SpotBookmarksController(SpotBookmarkService spotBookmarkService) {
        this.spotBookmarkService = spotBookmarkService;
    }

    @Operation(summary = "切换景点收藏", description = "需鉴权。已收藏则取消，未收藏则收藏；返回切换后状态。")
    @PostMapping("/spots/{slug}/bookmark")
    public ResponseEntity<SpotBookmarkStatusResponse> toggle(@PathVariable String slug) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(spotBookmarkService.toggle(slug, userId, Instant.now()));
    }

    @Operation(summary = "景点收藏状态", description = "需鉴权。返回当前用户是否已收藏该景点；不存在 404。")
    @GetMapping("/spots/{slug}/bookmark")
    public ResponseEntity<SpotBookmarkStatusResponse> status(@PathVariable String slug) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(SpotBookmarkStatusResponse.from(slug, spotBookmarkService.isBookmarked(slug, userId)));
    }

    @Operation(summary = "我的景点收藏列表", description = "需鉴权；按收藏时间倒序分页，返回景点列表项（仅 PUBLISHED）。")
    @GetMapping("/spot-bookmarks")
    public ResponseEntity<Page<SpotSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(spotBookmarkService.listSpotBookmarks(userId, page, size));
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new PlacesException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new PlacesException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
