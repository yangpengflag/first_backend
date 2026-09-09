package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.PostStatus;
import com.mooc.backend.entity.Spot;
import com.mooc.backend.entity.SpotStatus;
import com.mooc.backend.repository.CityRepository;
import com.mooc.backend.repository.PostRepository;
import com.mooc.backend.repository.SpotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 知识库索引同步 job（change: ai-rag，tasks 4.x / design.md D5；增量改造见
 * change: ai-rag-incremental-sync）。
 *
 * <p><b>同步策略</b>：
 * <ul>
 *   <li><b>增量</b>（有水位线）：只处理 {@code updated_at} 严格晚于水位线的实体——对每个变更实体
 *       先按其<b>来源键</b>删除既有检索块（city/spot 用 {@code slug}、post 用 {@code id}），
 *       再判断是否仍符合入索引条件（未软删 + PUBLISHED）：符合则重写，不符合则只删（墓碑语义）。
 *       变更集<b>不</b>过滤 deleted / status，否则已删内容的旧块会永久残留。</li>
 *   <li><b>全量兜底</b>（无水位线：首次运行 / 水位线丢失）：按类型分片全量替换，
 *       保证增量模式下也不会漏数据。</li>
 * </ul>
 * 旧块必须<b>按来源键</b>删而非按文档 id 列表：文档 id 为 {@code md5({type}:{key}:{seq})}，
 * 内容变短时切块数变少，只按 id 删会漏掉尾部旧块。
 *
 * <p><b>水位线纪律</b>：仅整轮成功才推进，且取<b>本轮开始时刻</b>（重复安全、遗漏致命）；
 * 失败不推进，下轮重放（先删后写，幂等）。
 *
 * <p>防护：{@code app.ai-rag.enabled=false} 或向量库不可用 → 跳过本轮（下轮自愈）；Redis 抢锁
 * 防多实例并发重建（fail-safe 方向同 travel {@code RefreshLock}：抢锁失败 = 跳过本轮，绝不当成拿到）；
 * 全程异常只告警不外抛，不冒泡到调度线程之外。启动不补建，靠周期任务与首次空窗降级。
 */
@Component
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    private static final String LOCK_KEY = "ai-rag:refresh-lock:knowledge";

    /** 增量同步水位线（Redis string，存 {@code Instant} 文本；丢失 = 退化为全量重建）。 */
    private static final String WATERMARK_KEY = "ai-rag:index-watermark";

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
    private final Clock clock;

    public KnowledgeIndexer(CityRepository cityRepository,
                            SpotRepository spotRepository,
                            PostRepository postRepository,
                            KnowledgeStore knowledgeStore,
                            AiRagProperties props,
                            StringRedisTemplate redis,
                            Clock clock) {
        this.cityRepository = cityRepository;
        this.spotRepository = spotRepository;
        this.postRepository = postRepository;
        this.knowledgeStore = knowledgeStore;
        this.props = props;
        this.redis = redis;
        this.clock = clock;
    }

    /** 周期入口（cron 可配，见 application.yml {@code app.ai-rag.refresh-cron}）。 */
    @Scheduled(cron = "${app.ai-rag.refresh-cron}")
    public void scheduledReindex() {
        reindex();
    }

    /** 幂等同步；可直接调用（测试 / 手工触发），行为与调度入口一致。 */
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
            Instant roundStart = clock.instant();
            Optional<Instant> watermark;
            try {
                watermark = readWatermark();
            } catch (DataAccessException e) {
                log.warn("[ai-rag] redis unavailable while reading {} (skip this round): {}",
                        WATERMARK_KEY, e.getMessage());
                return;
            }
            if (watermark.isPresent()) {
                syncChanged(vs, watermark.get());
            } else {
                fullRebuild(vs);
            }
            redis.opsForValue().set(WATERMARK_KEY, roundStart.toString());
        } catch (RuntimeException e) {
            // 带完整堆栈：spring-ai 的 "Failed to insert:" 等消息不含原因（cause 里才是真相）
            log.warn("[ai-rag] knowledge index refresh failed (will retry next round): {}",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    /** 全量兜底：按类型分片替换（首次运行 / 水位线丢失时走这条路径）。 */
    private void fullRebuild(VectorStore vs) {
        List<Document> cities = loadCities();
        List<Document> spots = loadPublishedSpots();
        List<Document> posts = loadPublishedPosts();
        replace(vs, "city", cities);
        replace(vs, "spot", spots);
        replace(vs, "post", posts);
        log.info("[ai-rag] knowledge index full rebuild (city={} spot={} post={} docs)",
                cities.size(), spots.size(), posts.size());
    }

    /** 增量：只处理变更实体——每个实体先按来源键删旧块，再按需重写。 */
    private void syncChanged(VectorStore vs, Instant watermark) {
        List<Document> toAdd = new ArrayList<>();
        int changed = 0;

        for (City city : cityRepository.findByUpdatedAtAfter(watermark)) {
            changed++;
            vs.delete(sourceFilter("city", "slug", city.getSlug()));
            if (!city.isDeleted()) {
                toAdd.addAll(KnowledgeDocumentMapper.mapCity(city, props.chunkMaxChars()));
            }
        }
        for (Spot spot : spotRepository.findByUpdatedAtAfter(watermark)) {
            changed++;
            vs.delete(sourceFilter("spot", "slug", spot.getSlug()));
            if (!spot.isDeleted() && spot.getStatus() == SpotStatus.PUBLISHED) {
                toAdd.addAll(KnowledgeDocumentMapper.mapSpot(spot, props.chunkMaxChars()));
            }
        }
        for (Post post : postRepository.findByUpdatedAtAfter(watermark)) {
            changed++;
            vs.delete(sourceFilter("post", "id", post.getId().toString()));
            if (!post.isDeleted() && post.getStatus() == PostStatus.PUBLISHED) {
                toAdd.addAll(KnowledgeDocumentMapper.mapPost(post, props.chunkMaxChars()));
            }
        }
        for (int i = 0; i < toAdd.size(); i += EMBED_BATCH_SIZE) {
            vs.add(toAdd.subList(i, Math.min(i + EMBED_BATCH_SIZE, toAdd.size())));
        }
        log.info("[ai-rag] knowledge index incrementally synced (changed={} docs={})", changed, toAdd.size());
    }

    /** 来源键过滤器：{@code type == x && keyField == keyValue}（按来源键删，覆盖切块数变化）。 */
    private Expression sourceFilter(String type, String keyField, String keyValue) {
        return new FilterExpressionBuilder()
                .and(new FilterExpressionBuilder().eq("type", type),
                        new FilterExpressionBuilder().eq(keyField, keyValue))
                .build();
    }

    private Optional<Instant> readWatermark() {
        String raw = redis.opsForValue().get(WATERMARK_KEY);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(raw));
        } catch (RuntimeException e) {
            log.warn("[ai-rag] unparsable watermark '{}' (falling back to full rebuild)", raw);
            return Optional.empty();
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
