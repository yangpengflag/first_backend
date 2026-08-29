package com.mooc.backend.auth.config;

import com.mooc.backend.auth.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 认证模块装配。
 *
 * <p>注入 {@link Clock} 而非直接调用 {@code Instant.now()}，
 * 使锁定、验证码过期等时间相关行为在测试中完全可控。
 */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class})
public class AuthConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
