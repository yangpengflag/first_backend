package com.mooc.backend.auth.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.ErrorResponse;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 认证端点限流过滤（防账号枚举与凭证爆破）。
 *
 * <p>仅施加于注册 / 登录 / 重发验证三个免鉴权端点；已登录用户的正常请求不受影响。
 * 登录与重发额外按 {@code (IP + email)} 计数，以拦截针对单个账号的定向爆破。
 *
 * <p><b>关键约束</b>：超限请求<b>不执行</b>密码校验，因此不产生 failedAttempts 副作用。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String PATH_REGISTER = "/api/auth/register";
    private static final String PATH_LOGIN = "/api/auth/login";
    private static final String PATH_RESEND = "/api/auth/resend-verification";
    private static final String PATH_FORGOT = "/api/auth/forgot-password";
    private static final String PATH_RESET = "/api/auth/reset-password";

    private static final Duration WINDOW_15M = Duration.ofMinutes(15);
    private static final Duration WINDOW_1H = Duration.ofHours(1);
    private static final Duration WINDOW_24H = Duration.ofHours(24);

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimiter rateLimiter,
                           ObjectMapper objectMapper,
                           RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isPost(request) || !isRateLimitedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);

        // 登录与重发需要读取 body 中的 email 做定向限流，
        // 故先包装为可重复读的请求，避免后续 Controller 读不到 body。
        if (PATH_LOGIN.equals(path) || PATH_RESEND.equals(path) || PATH_FORGOT.equals(path)) {
            CachedBodyRequest cached = new CachedBodyRequest(request);
            String email = User.normalizeEmail(extractEmail(cached));

            if (exceedsIpLimit(path, ip) || exceedsEmailLimit(path, ip, email)) {
                reject(response);
                return;
            }
            filterChain.doFilter(cached, response);
            return;
        }

        // 注册：仅 IP 维度
        if (exceedsIpLimit(path, ip)) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isPost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    private boolean isRateLimitedPath(String path) {
        return PATH_REGISTER.equals(path) || PATH_LOGIN.equals(path) || PATH_RESEND.equals(path)
                || PATH_FORGOT.equals(path) || PATH_RESET.equals(path);
    }

    private boolean exceedsIpLimit(String path, String ip) {
        String key = path + "|ip|" + ip;
        return switch (path) {
            case PATH_REGISTER -> !rateLimiter.tryAcquire(
                    key, properties.getRegisterPerIpPerHour(), WINDOW_1H);
            case PATH_LOGIN -> !rateLimiter.tryAcquire(
                    key, properties.getLoginPerIpPer15m(), WINDOW_15M);
            case PATH_RESEND -> !rateLimiter.tryAcquire(
                    key, properties.getResendPerIpPerHour(), WINDOW_1H);
            case PATH_FORGOT -> !rateLimiter.tryAcquire(
                    key, properties.getForgotPasswordPerIpPerHour(), WINDOW_1H);
            case PATH_RESET -> !rateLimiter.tryAcquire(
                    key, properties.getResetPasswordPerIpPerHour(), WINDOW_1H);
            default -> false;
        };
    }

    /** (IP + email) 维度；email 缺失时跳过该维度。 */
    private boolean exceedsEmailLimit(String path, String ip, String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String key = path + "|ip-email|" + ip + "|" + email;
        return switch (path) {
            case PATH_LOGIN -> !rateLimiter.tryAcquire(
                    key, properties.getLoginPerIpEmailPer15m(), WINDOW_15M);
            case PATH_RESEND -> !rateLimiter.tryAcquire(
                    key, properties.getResendPerIpEmailPer24h(), WINDOW_24H);
            case PATH_FORGOT -> !rateLimiter.tryAcquire(
                    key, properties.getForgotPasswordPerIpEmailPer24h(), WINDOW_24H);
            default -> false;
        };
    }

    private String extractEmail(HttpServletRequest request) {
        try {
            byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
            if (body.length == 0) {
                return null;
            }
            JsonNode node = objectMapper.readTree(body);
            JsonNode email = node.get("email");
            return email == null ? null : email.asText();
        } catch (Exception ex) {
            // body 非 JSON 或缺字段：交由后续参数校验处理，此处不做限流
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return Optional.ofNullable(request.getRemoteAddr()).orElse("unknown");
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.RATE_LIMITED.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(ErrorCode.RATE_LIMITED));
    }

    /** 包装请求以缓存 body，使其可被重复读取。 */
    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
