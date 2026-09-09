package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.PostStatus;
import com.mooc.backend.entity.Spot;
import com.mooc.backend.entity.SpotCategory;
import com.mooc.backend.entity.SpotStatus;
import com.mooc.backend.repository.CityRepository;
import com.mooc.backend.repository.PostRepository;
import com.mooc.backend.repository.SpotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 索引同步 job 契约（change: ai-rag，tasks 4.x / design.md D5）：按类型分片替换幂等、
 * 空列表也删（无残留）、不可用/redis 故障/开关关闭均跳过且不抛。
 */
class KnowledgeIndexerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AiRagProperties PROPS = new AiRagProperties(
            true, "localhost", 19530, 1024, "wanderchina-knowledge", 4, 0.0, 1200,
            "0 0 5 * * *", Duration.ofMinutes(5));

    private final CityRepository cityRepo = mock(CityRepository.class);
    private final SpotRepository spotRepo = mock(SpotRepository.class);
    private final PostRepository postRepo = mock(PostRepository.class);
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final VectorStore vs = mock(VectorStore.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private KnowledgeIndexer indexer(AiRagProperties props) {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        return new KnowledgeIndexer(cityRepo, spotRepo, postRepo, store, props, redis);
    }

    private void seedRepos(int cityCount, int spotCount, int postCount) {
        List<City> cities = java.util.stream.IntStream.range(0, cityCount)
                .mapToObj(i -> City.create(UUID.randomUUID(), "City" + i, "城" + i, "city" + i,
                        null, "A nice city " + i, "Spring", NOW)).toList();
        List<Spot> spots = java.util.stream.IntStream.range(0, spotCount)
                .mapToObj(i -> Spot.create(UUID.randomUUID(), "city0-spot" + i, "景点" + i, "Spot " + i,
                        "city0", SpotCategory.NATURE, List.of(), null, null, null, null, null, null, List.of(),
                        "Summary " + i, null, "Description " + i, null, null, null, null, null,
                        true, false, SpotStatus.PUBLISHED, NOW)).toList();
        List<Post> posts = java.util.stream.IntStream.range(0, postCount)
                .mapToObj(i -> Post.create(UUID.randomUUID(), "T" + i, "Body " + i, null, List.of(),
                        PostStatus.PUBLISHED, "city0", NOW)).toList();
        when(cityRepo.findByDeletedFalse(any())).thenReturn(new PageImpl<>(cities));
        when(spotRepo.findByStatusAndDeletedFalse(any(), any())).thenReturn(spots);
        when(postRepo.findByStatusAndDeletedFalse(any(), any())).thenReturn(new PageImpl<>(posts));
    }

    @Test
    void replacesEachTypeDeletingBeforeAdding() {
        indexer(PROPS);
        when(store.store()).thenReturn(Optional.of(vs));
        seedRepos(1, 1, 1);

        indexer(PROPS).reindex();

        verify(vs, times(3)).delete(any(Expression.class));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vs, times(3)).add(captor.capture());
        // city1→1 块、spot1→双语 2 块、post1→1 块；类型各自一致
        assertThat(captor.getAllValues().get(0)).hasSize(1).allMatch(d -> "city".equals(d.getMetadata().get("type")));
        assertThat(captor.getAllValues().get(1)).hasSize(2).allMatch(d -> "spot".equals(d.getMetadata().get("type")));
        assertThat(captor.getAllValues().get(2)).hasSize(1).allMatch(d -> "post".equals(d.getMetadata().get("type")));
    }

    @Test
    void manyDocumentsAreAddedInBatchesOfTen() {
        indexer(PROPS);
        when(store.store()).thenReturn(Optional.of(vs));
        seedRepos(0, 0, 12); // 12 篇 post → 12 文档 → 2 批（10 + 2）

        indexer(PROPS).reindex();

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vs, times(2)).add(captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(10);
        assertThat(captor.getAllValues().get(1)).hasSize(2);
        verify(vs, times(3)).delete(any(Expression.class));
    }

    @Test
    void emptySourceStillDeletesTypeToAvoidResidue() {
        indexer(PROPS);
        when(store.store()).thenReturn(Optional.of(vs));
        seedRepos(0, 0, 0);

        indexer(PROPS).reindex();

        verify(vs, times(3)).delete(any(Expression.class));
        verify(vs, never()).add(anyList());
    }

    @Test
    void storeUnavailableSkipsRoundWithoutTouchingRepos() {
        indexer(PROPS);
        when(store.store()).thenReturn(Optional.empty());
        seedRepos(2, 2, 2);

        indexer(PROPS).reindex();

        verifyNoInteractions(cityRepo);
        verify(vs, never()).delete(any(Expression.class));
    }

    @Test
    void redisFailureSkipsRoundAsFailSafe() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any()))
                .thenThrow(new DataAccessException("redis down") {
                });
        KnowledgeIndexer indexer = new KnowledgeIndexer(cityRepo, spotRepo, postRepo, store, PROPS, redis);

        indexer.reindex(); // 不抛
        verifyNoInteractions(store);
    }

    @Test
    void disabledSwitchDoesNothing() {
        AiRagProperties disabled = new AiRagProperties(
                false, "localhost", 19530, 1024, "wanderchina-knowledge", 4, 0.0, 1200,
                "0 0 5 * * *", Duration.ofMinutes(5));
        KnowledgeIndexer indexer = new KnowledgeIndexer(cityRepo, spotRepo, postRepo, store, disabled, redis);

        indexer.reindex();
        verifyNoInteractions(redis, store, cityRepo);
    }
}
