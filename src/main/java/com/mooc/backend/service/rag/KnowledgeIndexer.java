package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import com.mooc.backend.entity.PostStatus;
import com.mooc.backend.entity.SpotStatus;
import com.mooc.backend.repository.CityRepository;
import com.mooc.backend.repository.PostRepository;
import com.mooc.backend.repository.SpotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 知识库索引同步 job（change: ai-rag，tasks 4.x / design.md D5）。
 *
 * <p>同步策略：<b>按类型分片替换</b>（spike 2.3 实证 {@code delete(Filter eq type)} 可用）——
 * city / spot / post 各自先删该类型旧文档再全量 add，幂等、已删/转草稿内容不残留。
 *
 * <p>防护：{@code app.ai-rag.enabled=false} 或向量库不可用 → 跳过本轮（下轮自愈）；Redis 抢锁
 * 防多实例并发重建（fail-safe 方向同 travel {@code RefreshLock}：抢锁失败 = 跳过本轮，绝不当成拿到）；
 * 全程异常只告警不外抛，不冒泡到调度线程之外。启动不补建，靠周期任务与首次空窗降级。
 */
@Component
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    private static final String LOCK_KEY = "ai-rag:refresh-lock:knowledge";

    /**
     * DashScope compatible-mode embedding 批量上限（400 实测：batch > 10 被拒）。
     * spring-ai 的 VectorStore.add(list) 会把整批文本一次 embed，故入库前按此值分批。
     */
    private static final int EMBED_BATCH_SIZE = 10;

    private final CityRepository cityRepository;
    private final SpotRepository spotRepository;
    private final PostRepository postRepository;
    private final KnowledgeStore knowledgeStore;
    private final AiRagProperties props;
    private final StringRedisTemplate redis;

    public KnowledgeIndexer(CityRepository cityRepository,
                            SpotRepository spotRepository,
                            PostRepository postRepository,
                            KnowledgeStore knowledgeStore,
                            AiRagProperties props,
                            StringRedisTemplate redis) {
        this.cityRepository = cityRepository;
        this.spotRepository = spotRepository;
        this.postRepository = postRepository;
        this.knowledgeStore = knowledgeStore;
        this.props = props;
        this.redis = redis;
    }

    /** 周期入口（cron 可配，见 application.yml {@code app.ai-rag.refresh-cron}）。 */
    @Scheduled(cron = "${app.ai-rag.refresh-cron}")
    public void scheduledReindex() {
        reindex();
    }

    /** 幂等全量重建；可直接调用（测试 / 手工触发），行为与调度入口一致。 */
    public void reindex() {
        if (!props.enabled()) {
            return;
        }
        if (!acquireLock()) {
            log.info("[ai-rag] skip knowledge index round: lock not acquired");
            return;
        }
        try {
            Optional<VectorStore> maybe = knowledgeStore.store();
            if (maybe.isEmpty()) {
                log.info("[ai-rag] knowledge store not ready, skip this index round (will self-heal)");
                return;
            }
            VectorStore vs = maybe.get();
            List<Document> cities = loadCities();
            List<Document> spots = loadPublishedSpots();
            List<Document> posts = loadPublishedPosts();
            replace(vs, "city", cities);
            replace(vs, "spot", spots);
            replace(vs, "post", posts);
            log.info("[ai-rag] knowledge index refreshed (city={} spot={} post={} docs)",
                    cities.size(), spots.size(), posts.size());
        } catch (RuntimeException e) {
            // 带完整堆栈：spring-ai 的 "Failed to insert:" 等消息不含原因（cause 里才是真相）
            log.warn("[ai-rag] knowledge index refresh failed (will retry next round): {}",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    private List<Document> loadCities() {
        return cityRepository.findByDeletedFalse(Pageable.unpaged()).getContent().stream()
                .flatMap(c -> KnowledgeDocumentMapper.mapCity(c, props.chunkMaxChars()).stream())
                .toList();
    }

    private List<Document> loadPublishedSpots() {
        return spotRepository.findByStatusAndDeletedFalse(SpotStatus.PUBLISHED, Pageable.unpaged()).stream()
                .flatMap(s -> KnowledgeDocumentMapper.mapSpot(s, props.chunkMaxChars()).stream())
                .toList();
    }

    private List<Document> loadPublishedPosts() {
        return postRepository.findByStatusAndDeletedFalse(PostStatus.PUBLISHED, Pageable.unpaged()).getContent().stream()
                .flatMap(p -> KnowledgeDocumentMapper.mapPost(p, props.chunkMaxChars()).stream())
                .toList();
    }

    /** 先删该类型旧文档再按批 add（空列表也要删，保证已删内容不残留；分批受 embedding 批量上限约束）。 */
    private void replace(VectorStore vs, String type, List<Document> docs) {
        vs.delete(new FilterExpressionBuilder().eq("type", type).build());
        for (int i = 0; i < docs.size(); i += EMBED_BATCH_SIZE) {
            vs.add(docs.subList(i, Math.min(i + EMBED_BATCH_SIZE, docs.size())));
        }
    }

    /** Redis 抢主锁：SETNX + TTL（锁靠 TTL 自然过期，不主动释放——两轮最小间隔）。 */
    private boolean acquireLock() {
        try {
            String owner = Long.toString(System.currentTimeMillis());
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(LOCK_KEY, owner, props.refreshLockTtl()));
        } catch (DataAccessException e) {
            log.warn("[ai-rag] redis unavailable while acquiring {} (skip this round): {}",
                    LOCK_KEY, e.getMessage());
            return false;
        }
    }
}
