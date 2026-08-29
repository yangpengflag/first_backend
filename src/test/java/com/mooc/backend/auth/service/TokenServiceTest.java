package com.mooc.backend.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final String SECRET =
            "wanderchina-dev-jwt-secret-key-change-me-in-production-0123456789abcdef";
    private static final String OTHER_SECRET =
            "another-totally-different-secret-key-for-signature-mismatch-0123456789";

    private static final UUID USER_ID = UUID.randomUUID();

    private final TokenService tokenService =
            new TokenService(SECRET, 15, 7, java.time.Clock.systemUTC());

    @Test
    void accessTokenRoundTripsUserId() {
        String token = tokenService.generateAccessToken(USER_ID);

        assertThat(tokenService.isValid(token)).isTrue();
        assertThat(tokenService.parseUserId(token)).isEqualTo(USER_ID);
        assertThat(tokenService.parseTokenType(token)).isEqualTo(TokenService.TYPE_ACCESS);
    }

    @Test
    void refreshTokenCarriesRefreshType() {
        String token = tokenService.generateRefreshToken(USER_ID);

        assertThat(tokenService.parseTokenType(token)).isEqualTo(TokenService.TYPE_REFRESH);
        assertThat(tokenService.parseUserId(token)).isEqualTo(USER_ID);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = tokenService.generateAccessToken(USER_ID);
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThat(tokenService.isValid(tampered)).isFalse();
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        String foreignToken = Jwts.builder()
                .subject(USER_ID.toString())
                .claim(TokenService.CLAIM_TYPE, TokenService.TYPE_ACCESS)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(tokenService.isValid(foreignToken)).isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant alreadyExpired = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = Jwts.builder()
                .subject(USER_ID.toString())
                .claim(TokenService.CLAIM_TYPE, TokenService.TYPE_ACCESS)
                .issuedAt(Date.from(alreadyExpired.minus(1, ChronoUnit.HOURS)))
                .expiration(Date.from(alreadyExpired))
                .signWith(key)
                .compact();

        assertThat(tokenService.isValid(expired)).isFalse();
    }

    @Test
    void malformedOrBlankTokenIsRejected() {
        assertThat(tokenService.isValid(null)).isFalse();
        assertThat(tokenService.isValid("")).isFalse();
        assertThat(tokenService.isValid("   ")).isFalse();
        assertThat(tokenService.isValid("not-a-jwt")).isFalse();
    }

    @Test
    void accessAndRefreshTokensDiffer() {
        String access = tokenService.generateAccessToken(USER_ID);
        String refresh = tokenService.generateRefreshToken(USER_ID);

        // 过期时间与 typ 声明不同，故令牌串不应相同
        assertThat(access).isNotEqualTo(refresh);
    }
}
