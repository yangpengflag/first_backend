package com.mooc.backend.auth.security;

import com.mooc.backend.auth.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

/**
 * 解析 {@code Authorization: Bearer <token>}，将用户 UUID 置入 SecurityContext。
 *
 * <p><b>只承载身份，不承载状态</b>：令牌签名有效即建立认证，
 * 用户是否仍可访问由 {@link UserStatusFilter} 回查当前状态决定。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public JwtAuthFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (tokenService.isValid(token)) {
                try {
                    UUID userId = tokenService.parseUserId(token);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userId.toString(), null, Collections.emptyList());
                    // 携带签发时刻，供 UserStatusFilter 与密码变更时刻比对（改密即登出）
                    authentication.setDetails(tokenService.parseIssuedAt(token));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (IllegalArgumentException ex) {
                    // 令牌结构合法但 subject 不是 UUID：视为未认证
                    SecurityContextHolder.clearContext();
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
