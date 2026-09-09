package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.RetryParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * RAG 惰性向量句柄（change: ai-rag，tasks 2.4 / design.md D3；向量库现为 Milvus，
 * change: ai-rag-milvus）。
 *
 * <p><b>为什么惰性而非 {@code @Bean MilvusVectorStore}</b>（ai-rag spike 2.3 实证，Milvus 同构）：
 * {@code MilvusVectorStore} 实现 {@code InitializingBean}，{@code afterPropertiesSet()}
 * 即触发向量库网络（get-or-create collection + load）——作为普通 bean 会在 context 初始化期联网，
 * Milvus 宕机则 context 启动失败（fail-START），违背本仓 fail-closed 纪律。故这里把
 * 构建推迟到<b>首次被消费</b>：失败只记告警（不含 key / embedding 原文）+ 退避重试，
 * Milvus 恢复后自动自愈；对话主流程不感知（调用方拿到空即降级）。
 *
 * <p>构建与可用性守卫：{@code app.ai-rag.enabled=false} 或 DashScope api-key 为空 →
 * 不构建任何外部客户端（{@link #store()} 直接返回空）。
 */
@Component
public class KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStore.class);

    /** 构建失败后的重试退避：失败瞬间起 N 秒内不再联网重试。 */
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String EMBEDDING_MODEL = "text-embedding-v3";

    /**
     * Milvus SDK 重试上界（真机实证，2026-09-08）：默认策略在服务端不可达时会以 3s 间隔
     * <b>无封顶重试</b>（实测 74+ 次仍不停），检索调用因此悬挂数分钟——对话请求卡死，
     * 违背「检索故障静默降级」的及时性语义。故显式封顶，并同时收紧连接超时——
     * <b>实测真正的主因是 SDK 默认 connectTimeout = 10s</b>（而非重试次数）。
     * 最坏单次 RPC ≈ connectTimeout × (maxRetries + 1) ≈ 3s。Milvus 恢复后 gRPC 通道
     * 自动重建（自愈），无需重启应用。
     */
    private static final int MILVUS_MAX_RETRIES = 2;
    private static final long MILVUS_INITIAL_BACKOFF_MS = 100;
    private static final long MILVUS_MAX_BACKOFF_MS = 300;
    private static final long MILVUS_CONNECT_TIMEOUT_MS = 1000;

    private final AiRagProperties props;
    private final Environment env;
    private final Clock clock;

    private volatile VectorStore store;
    private volatile Instant failedAt;

    public KnowledgeStore(AiRagProperties props, Environment env, Clock clock) {
        this.props = props;
        this.env = env;
        this.clock = clock;
    }

    /**
     * 惰性获取向量库句柄：首次调用且条件就绪时构建（构建本身联网）；失败返回空并在
     * {@link #RETRY_BACKOFF} 内不再重试。成功构建后缓存，后续调用零网络。
     */
    public Optional<VectorStore> store() {
        VectorStore current = store;
        if (current != null) {
            return Optional.of(current);
        }
        if (!credentialsReady()) {
            return Optional.empty();
        }
        synchronized (this) {
            current = store;
            if (current == null) {
                current = tryInit();
            }
        }
        return Optional.ofNullable(current);
    }

    /** 条件守卫只读 env（沿用 weather 教训）：enabled 开关 + api-key 非空。 */
    private boolean credentialsReady() {
        if (!props.enabled()) {
            return false;
        }
        String apiKey = env.getProperty("spring.ai.openai.api-key", "");
        return apiKey != null && !apiKey.isBlank();
    }

    private VectorStore tryInit() {
        Instant now = clock.instant();
        if (failedAt != null && now.isBefore(failedAt.plus(RETRY_BACKOFF))) {
            return null;
        }
        try {
            VectorStore built = createMilvusStore();
            store = built;
            failedAt = null;
            log.info("Knowledge store initialised (collection '{}' at {}:{})",
                    props.collectionName(), props.milvusHost(), props.milvusPort());
            return built;
        } catch (RuntimeException e) {
            failedAt = clock.instant();
            log.warn("Knowledge store unavailable, will retry in {}s: {}", RETRY_BACKOFF.getSeconds(),
                    safeMessage(e));
            return null;
        }
    }

    /**
     * 构建 embedding（DashScope compatible-mode text-embedding-v3，1024 维）+ Milvus 向量库
     * （gRPC，集合维度显式指定——Milvus 建集合时 schema 固定，缺省兜底 1536 会建错）。
     * 独立方法供测试覆写（桩返回 / 模拟故障），生产走真实构建。
     */
    protected VectorStore createMilvusStore() {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(env.getProperty("spring.ai.openai.base-url", DEFAULT_BASE_URL))
                .apiKey(env.getProperty("spring.ai.openai.api-key", ""))
                .build();
        EmbeddingModel embedding = new OpenAiEmbeddingModel(
                api, MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(EMBEDDING_MODEL).build());
        MilvusServiceClient base = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(props.milvusHost())
                .withPort(props.milvusPort())
                .withConnectTimeout(MILVUS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build());
        // 必须封顶重试：默认策略下 Milvus 宕机会令 RPC 悬挂数分钟（见常量注释）。
        // 陷阱（bytecode 实证）：withRetry 不改自身，而是返回<b>新的 client 副本</b>——
        // 丢弃返回值等于没设，故必须接住返回值（返回的正是 MilvusServiceClient）。
        MilvusServiceClient milvusClient = (MilvusServiceClient) base.withRetry(RetryParam.newBuilder()
                .withMaxRetryTimes(MILVUS_MAX_RETRIES)
                .withInitialBackOffMs(MILVUS_INITIAL_BACKOFF_MS)
                .withMaxBackOffMs(MILVUS_MAX_BACKOFF_MS)
                .build());
        MilvusVectorStore vectorStore = MilvusVectorStore.builder(milvusClient, embedding)
                .collectionName(props.collectionName())
                .embeddingDimension(props.embeddingDimension())
                .initializeSchema(true)
                .build();
        boolean initialised = false;
        try {
            vectorStore.afterPropertiesSet();
            initialised = true;
            return vectorStore;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise Milvus collection", e);
        } finally {
            // 构建失败必须关闭 gRPC 通道：Milvus 宕机期间每 30s 重试一次，
            // 未关闭会持续堆积连接（句柄泄漏）。成功时向量库仍持有该 client，不能关。
            if (!initialised) {
                closeQuietly(milvusClient);
            }
        }
    }

    /** 关闭向量库客户端，异常不外抛（清理路径不该影响主流程语义）。 */
    private void closeQuietly(MilvusServiceClient client) {
        try {
            client.close(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("Closing milvus client failed: {}", e.getMessage());
        }
    }

    /** 失败摘要：截断防刷屏；不含 key（消息源为 gRPC / 向量库错误体，不含凭据）。 */
    private String safeMessage(RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        return msg.length() <= 300 ? msg : msg.substring(0, 300);
    }
}
