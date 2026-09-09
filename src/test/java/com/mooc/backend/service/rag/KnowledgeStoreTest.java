package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 惰性向量句柄契约（change: ai-rag，tasks 2.4）：条件守卫零网络、首用构建缓存一次、
 * 构建失败退避重试并自愈。生产构建路径（真实 embedding + Milvus）由 ai-rag-milvus tasks 5.2 真机联调覆盖。
 */
class KnowledgeStoreTest {

    private static final String KEY = "spring.ai.openai.api-key";

    /** 可拨动时钟，使退避窗口可测。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private static AiRagProperties props(boolean enabled) {
        return new AiRagProperties(enabled, "localhost", 19530, 1024, "wanderchina-knowledge",
                4, 0.0, 1200, "0 0 5 * * *", Duration.ofMinutes(5));
    }

    private static MockEnvironment envWithKey() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(KEY, "sk-test-dummy");
        return env;
    }

    /** 记录真实构建尝试次数；工厂可先抛后成，模拟瞬时故障后的自愈。 */
    private static final class ProbeStore extends KnowledgeStore {
        int attempts;
        boolean failNext;
        final VectorStore fake = mock(VectorStore.class);

        ProbeStore(AiRagProperties props, MockEnvironment env, MutableClock clock) {
            super(props, env, clock);
        }

        @Override
        protected VectorStore createMilvusStore() {
            attempts++;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("milvus down (spike simulation)");
            }
            return fake;
        }
    }

    @Test
    void disabledSwitchDoesNotBuildAnyExternalClient() {
        MutableClock clock = new MutableClock();
        ProbeStore store = new ProbeStore(props(false), envWithKey(), clock);

        assertThat(store.store()).isEmpty();
        assertThat(store.attempts).isZero();
    }

    @Test
    void missingApiKeyDoesNotBuildAnyExternalClient() {
        MutableClock clock = new MutableClock();
        ProbeStore store = new ProbeStore(props(true), new MockEnvironment(), clock);

        assertThat(store.store()).isEmpty();
        assertThat(store.attempts).isZero();
    }

    @Test
    void firstUseBuildsOnceAndCaches() {
        MutableClock clock = new MutableClock();
        ProbeStore store = new ProbeStore(props(true), envWithKey(), clock);

        assertThat(store.store()).containsSame(store.fake);
        assertThat(store.attempts).isEqualTo(1);

        // 缓存：再次调用与时间推移均不重建
        clock.advance(Duration.ofMinutes(10));
        assertThat(store.store()).containsSame(store.fake);
        assertThat(store.attempts).isEqualTo(1);
    }

    @Test
    void failureBacksOffWithinWindowThenSelfHeals() {
        MutableClock clock = new MutableClock();
        ProbeStore store = new ProbeStore(props(true), envWithKey(), clock);
        store.failNext = true;

        // 首次失败 → 空；退避窗口内不联网重试
        assertThat(store.store()).isEmpty();
        assertThat(store.attempts).isEqualTo(1);
        assertThat(store.store()).isEmpty();
        assertThat(store.attempts).isEqualTo(1);

        // 越过退避窗口 → 重试成功，返回真实句柄（自愈）
        clock.advance(Duration.ofSeconds(31));
        assertThat(store.store()).containsSame(store.fake);
        assertThat(store.attempts).isEqualTo(2);
    }
}
