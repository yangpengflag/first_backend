package com.mooc.backend.places.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.service.SpotService;
import com.mooc.backend.places.service.ViewCountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 景点 POI HTTP 接口。
 *
 * <p>读：列表 / 详情，公开免鉴权；详情访问计数通过 {@code ViewCountService} 异步落库。
 * 写：POST /api/spots、PUT /api/spots/{slug}，需认证（SecurityConfig 默认 authenticated）；
 * 控制器取 userId 仅作鉴权凭证，不持久化到 Spot（CMS POI 无需归属）。
 * 错误经 {@code GlobalExceptionHandler}（未知 slug → 404 + SPOT_NOT_FOUND；slug 冲突 → 409 + SPOT_SLUG_CONFLICT）。
 */
@RestController
@RequestMapping(value = "/api/spots", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "景点 POI", description = "中国具体可游览 POI 的公开列表与详情，及认证写 API（创建 / 更新）。")
public class SpotsController {

    private final SpotService spotService;
    private final ViewCountService viewCountService;

    public SpotsController(SpotService spotService, ViewCountService viewCountService) {
        this.spotService = spotService;
        this.viewCountService = viewCountService;
    }

    @Operation(summary = "景点列表", description = "支持 city / category / tag / q 筛选；sort=popular（默认，view_count 降序）/ hidden（小众优先）；page/size 分页。公开免鉴权。")
    @GetMapping
    public ResponseEntity<SpotListResponse> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "popular") String sort,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(spotService.list(city, category, tag, q, sort, page, size));
    }

    @Operation(summary = "景点详情", description = "返回景点信息、周边 POI 与相关攻略占位；访问计数异步 +1。公开免鉴权。")
    @GetMapping("/{slug}")
    public ResponseEntity<SpotDetail> get(@PathVariable String slug, HttpServletRequest request) {
        CompletableFuture.runAsync(() -> viewCountService.recordSpotView(slug, clientIp(request)));
        return ResponseEntity.ok(spotService.getBySlug(slug));
    }

    @Operation(summary = "景点排行榜", description = "公开免鉴权；type=rating|popular|bookmarks（默认 popular），limit 默认 10 上限 50。返回 SpotSummary 数组（Top N）。数据经缓存提供、最多滞后 5 分钟；景点写操作与收藏切换即时失效（下个请求即最新）。")
    @GetMapping("/ranking")
    public ResponseEntity<List<SpotSummary>> ranking(
            @RequestParam(required = false, defaultValue = "popular") String type,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return ResponseEntity.ok(spotService.ranking(type, limit));
    }

    @Operation(summary = "创建景点", description = "需认证（JWT）。slug 由 {citySlug}-{slugify(nameEn)} 推导，冲突 409；creator 不持久化。")
    @PostMapping
    public ResponseEntity<SpotDetail> create(@Valid @RequestBody CreateSpotRequest request) {
        currentUserId(); // 仅鉴权凭证，不持久化 creator
        return ResponseEntity.status(HttpStatus.CREATED).body(spotService.create(request, Instant.now()));
    }

    @Operation(summary = "更新景点", description = "需认证（JWT）。补丁式：null 保留原值；slug 不可变。")
    @PutMapping("/{slug}")
    public ResponseEntity<SpotDetail> update(@PathVariable String slug, @Valid @RequestBody UpdateSpotRequest request) {
        currentUserId();
        return ResponseEntity.ok(spotService.update(slug, request, Instant.now()));
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 从 SecurityContext 取当前用户标识（JWT 主体为 UUID 字符串），缺失 / 非法 → 401 UNAUTHENTICATED。 */
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
