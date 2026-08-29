package com.mooc.backend.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>令牌<b>只承载用户标识</b>，不承载状态/角色——用户状态一律由
 * {@code UserStatusFilter} 回查数据库判定，以保证锁定与注销即时生效
 * （JWT 无状态与「即时吊销」的张力详见 design.md）。
 */
@Service
public class TokenService {

    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;
    private final Clock clock;

    public TokenService(
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.access-token-ttl-minutes:15}") int accessTtlMinutes,
            @Value("${auth.jwt.refresh-token-ttl-days:7}") int refreshTtlDays,
            Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
        this.clock = clock;
    }

    public String generateAccessToken(UUID userId) {
        return build(userId, TYPE_ACCESS, clock.instant().plus(accessTtlMinutes, ChronoUnit.MINUTES));
    }

    public String generateRefreshToken(UUID userId) {
        return build(userId, TYPE_REFRESH, clock.instant().plus(refreshTtlDays, ChronoUnit.DAYS));
    }

    /** 解析出用户标识；令牌无效、过期或签名不符时抛出。 */
    public UUID parseUserId(String token) {
        return UUID.fromString(claimsOf(token).getSubject());
    }

    public String parseTokenType(String token) {
        return claimsOf(token).get(CLAIM_TYPE, String.class);
    }

    /**
     * 令牌签发时刻。
     *
     * <p>用于与用户的 {@code passwordChangedAt} 比对：签发早于密码变更的令牌
     * 应判定失效，从而在无状态 JWT 下实现「改密即登出」。
     */
    public Instant parseIssuedAt(String token) {
        Date issuedAt = claimsOf(token).getIssuedAt();
        return issuedAt == null ? null : issuedAt.toInstant();
    }

    /** 令牌是否可被信任（签名有效且未过期）。 */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            claimsOf(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private String build(UUID userId, String type, Instant expiry) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验令牌。
     *
     * <p><b>关键</b>：过期判定注入与签发相同的 {@code clock}（{@code io.jsonwebtoken.Clock}）。
     * 否则 JJWT 默认按系统真实时间判过期，而令牌的 iat/exp 来自业务时钟，
     * 两者不同源会导致令牌被误判过期。
     */
    private Claims claimsOf(String token) {
        return Jwts.parser()
                .clock(() -> Date.from(clock.instant()))
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
