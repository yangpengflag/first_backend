package com.mooc.backend.places.api;

import com.mooc.backend.places.service.SpotService;
import com.mooc.backend.places.service.ViewCountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * 景点 POI HTTP 接口（只读，公开免鉴权）。
 *
 * <p>详情访问计数通过 {@code ViewCountService} 异步落库，不阻塞响应；防刷按 {@code (slug, clientIp)} 限频。
 * 错误经 {@code GlobalExceptionHandler}（未知 slug → 404 + {@code SPOT_NOT_FOUND}）。
 */
@RestController
@RequestMapping(value = "/api/spots", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "景点 POI", description = "中国具体可游览 POI 的公开列表与详情（含周边 POI 与相关攻略占位）。")
public class SpotsController {

    private final SpotService spotService;
    private final ViewCountService viewCountService;

    public SpotsController(SpotService spotService, ViewCountService viewCountService) {
        this.spotService = spotService;
        this.viewCountService = viewCountService;
    }

    @Operation(summary = "景点列表", description = "支持 city / category / tag / q 筛选；sort=popular（默认，view_count 降序）/ hidden（小众优先）；page/size 分页。")
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

    @Operation(summary = "景点详情", description = "返回景点信息、周边 POI 与相关攻略占位；访问计数异步 +1。")
    @GetMapping("/{slug}")
    public ResponseEntity<SpotDetail> get(@PathVariable String slug, HttpServletRequest request) {
        CompletableFuture.runAsync(() -> viewCountService.recordSpotView(slug, clientIp(request)));
        return ResponseEntity.ok(spotService.getBySlug(slug));
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
