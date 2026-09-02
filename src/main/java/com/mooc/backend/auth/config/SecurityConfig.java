package com.mooc.backend.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.ErrorResponse;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.auth.ratelimit.RateLimitFilter;
import com.mooc.backend.auth.security.JwtAuthFilter;
import com.mooc.backend.auth.security.UserStatusFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p>采用 JWT 无状态方案：禁用 Session 与 CSRF。过滤链顺序：
 * <pre>
 *   RateLimitFilter → JwtAuthFilter → UserStatusFilter → DispatcherServlet
 * </pre>
 *
 * <p>注册 / 登录 / 邮箱验证 / 重发 / 刷新令牌为免鉴权端点，其余需有效令牌，
 * 且令牌对应用户的<b>当前</b>状态必须为 ACTIVE。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 免鉴权端点。
     *
     * <p>末尾四项为 OpenAPI 文档路径（change: openapi-integration）。它们必须显式放行：
     * 若不放行，{@code anyRequest().authenticated()} 会令未认证请求返回 <b>401</b>，
     * 而 401 会泄露「该路径存在」这一事实，反而不如 404 安全。放行后 prod 下
     * （springdoc 已禁用）请求落到资源未注册的 <b>404</b>，既满足 spec 也不泄露信息。
     */
    public static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/verify",
            "/api/auth/resend-verification",
            "/api/auth/refresh",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    private final int bcryptStrength;
    private final List<String> allowedOrigins;
    private final ObjectMapper objectMapper;

    public SecurityConfig(@Value("${auth.bcrypt.strength:10}") int bcryptStrength,
                          @Value("${app.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins,
                          ObjectMapper objectMapper) {
        this.bcryptStrength = bcryptStrength;
        this.allowedOrigins = allowedOrigins;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RateLimitFilter rateLimitFilter,
                                           JwtAuthFilter jwtAuthFilter,
                                           UserStatusFilter userStatusFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // 脚手架遗留端点，与本 change 无关，保持其原有可访问性
                        .requestMatchers("/api/hello").permitAll()
                        // 帖子公开读端点（列表 / 详情）免鉴权；写操作与 /me 仍走 anyRequest().authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/posts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/me").authenticated()
                        // 帖子收藏状态查询需鉴权：精确路径须排在下方 GET /api/posts/* 公开读通配之前
                        .requestMatchers(HttpMethod.GET, "/api/posts/*/bookmark").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/posts/*").permitAll()
                        // 城市 / 景点公开读端点（列表 / 详情）免鉴权（change: api-spots）
                        .requestMatchers(HttpMethod.GET, "/api/cities").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/cities/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/spots").permitAll()
                        // 景点收藏状态查询需鉴权：精确路径须排在下方 GET /api/spots/* 公开读通配之前
                        .requestMatchers(HttpMethod.GET, "/api/spots/*/bookmark").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/spots/*").permitAll()
                        .anyRequest().authenticated()
                )
                // Spring Security 默认对未认证请求返回 403；本项目要求 401
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint()))
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthFilter, RateLimitFilter.class)
                .addFilterAfter(userStatusFilter, JwtAuthFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            response.setStatus(ErrorCode.UNAUTHENTICATED.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(),
                    ErrorResponse.of(ErrorCode.UNAUTHENTICATED));
        };
    }

    /**
     * 跨域配置：前端与后端不同源，必须显式放行前端来源，否则浏览器拦截全部请求。
     *
     * <p>来源列表由 {@code app.cors.allowed-origins} 提供，<b>禁止</b>配置为 {@code *}——
     * 通配符会使任意站点都能调用本服务接口。
     * 令牌经 {@code Authorization} 头传递（不使用 Cookie），故无需开启凭证。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }
}
