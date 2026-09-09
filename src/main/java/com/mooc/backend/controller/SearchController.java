package com.mooc.backend.controller;

import com.mooc.backend.config.SearchProperties;
import com.mooc.backend.dto.response.SearchResponse;
import com.mooc.backend.exception.ErrorCode;
import com.mooc.backend.exception.SearchException;
import com.mooc.backend.service.RateLimiter;
import com.mooc.backend.service.search.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 混合搜索 HTTP 接口（change: ai-semantic-search，tasks 4.1 / 4.3）。
 *
 * <p><b>公开免鉴权</b>（SecurityConfig 放行 {@code GET /api/search}）；IP 维度限流
 * （复用 {@code RateLimiter}，可经 {@code app.search.rate-limit-enabled} 关闭——本地零摩擦）。
 * 向量腿不可用 / ai-rag 关闭时 service 层自动退纯关键词，本端点<b>恒 200</b>（fail-open）。
 *
 * <p>参数约定：{@code q} 必填（去空白后非空，≤200）；{@code types} 可选白名单
 * {@code city|spot|post}（逗号分隔）；{@code limit} 可选，钳制 [1,20] 默认 10。
 */
@RestController
@RequestMapping(value = "/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "站内搜索", description = "自然语言 + 关键词混合检索（city / spot / post 跨类型融合）。")
public class SearchController {

    private static final int Q_MAX_CHARS = 200;
    private static final int LIMIT_DEFAULT = 10;
    private static final int LIMIT_MAX = 20;
    private static final Set<String> VALID_TYPES = Set.of("city", "spot", "post");

    private final SearchService searchService;
    private final RateLimiter rateLimiter;
    private final SearchProperties properties;

    public SearchController(SearchService searchService, RateLimiter rateLimiter,
                            SearchProperties properties) {
        this.searchService = searchService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Operation(summary = "混合搜索", description = "自然语言 + 关键词混合检索（向量 + LIKE 双路，RRF 融合）。公开免鉴权。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "融合结果（向量腿不可用时退纯关键词，仍 200）"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：q 缺失/空白/超长，或 types 含非法取值"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：请求过于频繁")
    })
    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String types,
            @RequestParam(required = false, defaultValue = "" + LIMIT_DEFAULT) int limit,
            HttpServletRequest request) {

        if (properties.rateLimitEnabled() && !rateLimiter.tryAcquire(
                "search|ip|" + request.getRemoteAddr(),
                properties.rateLimitPerMinute(), Duration.ofMinutes(1))) {
            throw new SearchException(ErrorCode.RATE_LIMITED);
        }

        if (q == null || q.isBlank()) {
            throw new SearchException(ErrorCode.VALIDATION_FAILED, "q is required.");
        }
        String query = q.trim();
        if (query.length() > Q_MAX_CHARS) {
            throw new SearchException(ErrorCode.VALIDATION_FAILED,
                    "q must be at most " + Q_MAX_CHARS + " characters.");
        }

        List<String> typeList = parseTypes(types);
        int capped = Math.max(1, Math.min(limit, LIMIT_MAX));
        return searchService.search(query, typeList, capped);
    }

    /** types 白名单校验：空 / 缺省 → 全类型；含非法取值 → 400（指名非法值，便于客户端纠错）。 */
    private List<String> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return List.of();
        }
        List<String> values = Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        List<String> invalid = values.stream()
                .filter(v -> !VALID_TYPES.contains(v))
                .toList();
        if (!invalid.isEmpty()) {
            throw new SearchException(ErrorCode.VALIDATION_FAILED,
                    "types contains invalid value(s): " + String.join(", ", invalid)
                            + ". Valid values: city, spot, post.");
        }
        return values;
    }
}
