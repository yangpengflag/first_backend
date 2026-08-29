package com.mooc.backend.auth.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存滑动窗口限流器。
 *
 * <p>每个 key 维护一个时间戳队列；每次访问先淘汰窗口外的记录，
 * 再判断队列长度是否已达上限。相比固定窗口，滑动窗口不会出现
 * 「窗口交界处双倍流量」的问题。
 *
 * <p><b>已知限制</b>：状态存于 JVM 内存，多实例部署时各实例独立计数。
 * 当前后端为单实例自托管，可接受；横向扩展时需替换为 Redis 实现。
 */
@Component
public class RateLimiter {

    private final Clock clock;
    private final ConcurrentMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 尝试记录一次命中。
     *
     * @return {@code true} 未超限（已计入）；{@code false} 已超限（<b>不</b>计入）
     */
    public boolean tryAcquire(String key, int maxHits, Duration window) {
        Instant now = clock.instant();
        Deque<Instant> bucket = hits.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (bucket) {
            evictExpired(bucket, now, window);
            if (bucket.size() >= maxHits) {
                return false;
            }
            bucket.addLast(now);
            return true;
        }
    }

    /** 当前窗口内累计命中次数。 */
    public int count(String key, Duration window) {
        Deque<Instant> bucket = hits.get(key);
        if (bucket == null) {
            return 0;
        }
        synchronized (bucket) {
            evictExpired(bucket, clock.instant(), window);
            return bucket.size();
        }
    }

    /** 清空所有计数（测试用）。 */
    public void reset() {
        hits.clear();
    }

    private void evictExpired(Deque<Instant> bucket, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        while (!bucket.isEmpty() && bucket.peekFirst().isBefore(cutoff)) {
            bucket.pollFirst();
        }
    }
}
