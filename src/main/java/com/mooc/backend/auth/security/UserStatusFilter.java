package com.mooc.backend.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.ErrorResponse;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.auth.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>用户状态守卫</b>——解决「JWT 无状态」与「锁定/注销需即时生效」的核心张力。
 *
 * <p>令牌签名有效<b>不等于</b>请求可放行：每个携带凭证的请求都会回查用户当前状态，
 * 状态非 {@code ACTIVE} 时立即短路，请求不会进入任何 Controller。
 *
 * <p>响应码映射：DELETED → 401、LOCKED → 423、EMAIL_UNVERIFIED → 403。
 *
 * <p>本过滤器位于 {@code DispatcherServlet} 之前，异常不会被
 * {@code GlobalExceptionHandler} 捕获，因此在此自行写出错误信封。
 */
@Component
public class UserStatusFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public UserStatusFilter(UserRepository userRepository, ObjectMapper objectMapper, Clock clock) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 无凭证的请求交给 Spring Security 的授权规则处理（免鉴权端点放行，其余 401）
        if (authentication == null || authentication.getName() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            reject(response, ErrorCode.UNAUTHENTICATED);
            return;
        }

        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            reject(response, ErrorCode.UNAUTHENTICATED);
            return;
        }
        User user = found.get();
        Instant now = clock.instant();

        // 锁定期已届满 → 自动解锁，避免用户被永久挡在门外
        if (user.getStatus() == UserStatus.LOCKED && !user.isLocked(now)) {
            user.unlockIfExpired(now);
            userRepository.save(user);
        }

        if (user.getStatus() == UserStatus.DELETED) {
            reject(response, ErrorCode.ACCOUNT_DELETED);
            return;
        }
        if (user.isLocked(now)) {
            long seconds = Duration.between(now, user.getLockedUntil()).getSeconds();
            reject(response, ErrorCode.ACCOUNT_LOCKED,
                    Map.of("retryAfterSeconds", Math.max(seconds, 0)));
            return;
        }
        if (user.getStatus() == UserStatus.EMAIL_UNVERIFIED) {
            reject(response, ErrorCode.EMAIL_NOT_VERIFIED);
            return;
        }

        /*
         * 令牌签发于密码变更之前 → 已作废。
         * 这是无状态 JWT 下的「改密即登出」：阻断攻击者持旧令牌持续访问。
         * 签发时刻由 JwtAuthFilter 写入 authentication details。
         */
        if (authentication.getDetails() instanceof Instant issuedAt
                && user.isTokenIssuedBeforePasswordChange(issuedAt)) {
            reject(response, ErrorCode.TOKEN_INVALIDATED);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** 短路并写出统一错误信封；同时清空认证上下文。 */
    private void reject(HttpServletResponse response, ErrorCode code) throws IOException {
        reject(response, code, null);
    }

    private void reject(HttpServletResponse response, ErrorCode code, Object details) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(code, code.getDefaultMessage(), details));
    }
}
