package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量库不可达时的<b>降级及时性</b>契约（change: ai-rag-milvus，design D3 实证收口）。
 *
 * <p>背景：Milvus SDK 默认在服务端不可达时以 3s 间隔<b>无封顶重试</b>（实测 74+ 次），
 * 且默认 connectTimeout 为 10s——检索调用会悬挂数分钟，对话请求被拖死，违背
 * 「检索故障静默降级（对话照常）」的及时性语义。{@code KnowledgeStore} 构建客户端时
 * 显式封顶重试 + 收紧连接超时，本用例锁定该行为。
 *
 * <p>本用例<b>不依赖真实 Milvus</b>：连接 19531（正常环境无监听），CI 安全；
 * 断言的是"失败要快"，不是"失败"。
 */
class KnowledgeStoreMilvusUnreachableTest {

    @Test
    void unreachableMilvusFailsFastInsteadOfHanging() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.openai.api-key", "sk-dummy");
        env.setProperty("spring.ai.openai.base-url", "https://dashscope.aliyuncs.com/compatible-mode");
        AiRagProperties props = new AiRagProperties(true, "localhost", 19531, 1024,
                "wanderchina_knowledge", 4, 0.0, 1200, "0 0 5 * * *", Duration.ofMinutes(5));
        KnowledgeStore store = new KnowledgeStore(props, env, Clock.systemUTC());

        long start = System.nanoTime();
        Optional<VectorStore> result = store.store();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEmpty();
        // 判别性：未封顶时实测 10s+（且 SDK 重试无上限时达分钟级）
        assertThat(elapsedMs).isLessThan(5_000);
    }
}
