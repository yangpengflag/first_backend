package com.mooc.backend.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 供所有认证集成测试共享的时钟配置。
 *
 * <p>抽成独立配置类（而非每个测试写内部类）是为了让 Spring 的上下文缓存
 * 能够复用——否则每个测试类都会启动一次完整应用上下文。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final Instant FIXED_NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Bean
    @Primary
    public Clock mutableClock() {
        return new MutableClock(FIXED_NOW);
    }
}
