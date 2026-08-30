package com.mooc.backend.votes.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.auth.ratelimit.RateLimiter;
import com.mooc.backend.auth.ratelimit.RateLimitProperties;
import com.mooc.backend.votes.domain.VoteType;
import com.mooc.backend.votes.exception.VoteException;
import com.mooc.backend.votes.service.VoteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 投票 HTTP 接口。所有端点均需 JWT 鉴权；{@code userId} 由 JWT 主体推导。
 *
 * <p>限流：{@code POST /vote} 在鉴权后按用户维度限流（复用 {@code RateLimiter}）。
 * 不得在 {@code RateLimitFilter} 内做用户维度限流——该过滤器位于 JwtAuthFilter 之前，主体尚未解析。
 */
@Tag(name = "投票", description = "帖子点赞 / 点踩的投票、取消、切换与统计。所有错误响应使用统一信封。")
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class VotesController {

    private final VoteService voteService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public VotesController(VoteService voteService, RateLimiter rateLimiter, RateLimitProperties rateLimitProperties) {
        this.voteService = voteService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Operation(summary = "投票（创建 / 切换 / 取消）", description = "需鉴权。同类型再投取消，异类型切换。按用户维度限流。")
    @PostMapping("/posts/{postId}/vote")
    public ResponseEntity<VoteResponse> vote(
            @PathVariable UUID postId,
            @Valid @RequestBody VoteRequest request) {
        UUID userId = currentUserId();
        String key = "vote|user|" + userId;
        if (!rateLimiter.tryAcquire(key, rateLimitProperties.getVotePerUserPerMinute(), Duration.ofMinutes(1))) {
            throw new VoteException(ErrorCode.RATE_LIMITED);
        }
        VoteResponse body = voteService.vote(postId, userId, request.voteType(), Instant.now());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "投票统计", description = "需鉴权。返回 UP/DOWN 总数与当前用户投票态。")
    @GetMapping("/posts/{postId}/vote/stats")
    public ResponseEntity<VoteStatsResponse> stats(@PathVariable UUID postId) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(voteService.getVoteStats(postId, userId));
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new VoteException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new VoteException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
