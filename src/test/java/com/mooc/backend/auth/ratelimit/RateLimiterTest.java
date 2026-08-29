package com.mooc.backend.auth.ratelimit;

import com.mooc.backend.auth.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 滑动窗口限流器（Task 9.1）。 */
class RateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private MutableClock clock;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        limiter = new RateLimiter(clock);
    }

    @Test
    void allowsUpToLimitThenRejects() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, WINDOW)).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isFalse();
    }

    /** 超限的尝试不计入，避免拉长封锁时间。 */
    @Test
    void rejectedAttemptsAreNotCounted() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k", 5, WINDOW);
        }
        limiter.tryAcquire("k", 5, WINDOW);
        limiter.tryAcquire("k", 5, WINDOW);

        assertThat(limiter.count("k", WINDOW)).isEqualTo(5);
    }

    /** 窗口滑动：越过窗口后额度恢复。 */
    @Test
    void windowSlidesSoOldHitsExpire() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k", 5, WINDOW);
        }
        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isFalse();

        clock.advance(Duration.ofMinutes(16));

        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isTrue();
        assertThat(limiter.count("k", WINDOW)).isEqualTo(1);
    }

    @Test
    void hitsJustInsideWindowStillCount() {
        limiter.tryAcquire("k", 5, WINDOW);
        limiter.tryAcquire("k", 5, WINDOW);

        clock.advance(Duration.ofMinutes(14));

        assertThat(limiter.count("k", WINDOW)).isEqualTo(2);
    }

    @Test
    void differentKeysAreCountedIndependently() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("ip|1.2.3.4", 5, WINDOW);
        }

        assertThat(limiter.tryAcquire("ip|1.2.3.4", 5, WINDOW)).isFalse();
        assertThat(limiter.tryAcquire("ip|5.6.7.8", 5, WINDOW)).isTrue();
    }

    @Test
    void resetClearsAllCounters() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k", 5, WINDOW);
        }

        limiter.reset();

        assertThat(limiter.count("k", WINDOW)).isZero();
        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isTrue();
    }

    @Test
    void countOfUnknownKeyIsZero() {
        assertThat(limiter.count("never-used", WINDOW)).isZero();
    }
}
