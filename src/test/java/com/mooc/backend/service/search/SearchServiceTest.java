package com.mooc.backend.service.search;

import com.mooc.backend.config.SearchProperties;
import com.mooc.backend.dto.response.SearchResponse;
import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.PostStatus;
import com.mooc.backend.entity.Spot;
import com.mooc.backend.entity.SpotStatus;
import com.mooc.backend.repository.CityRepository;
import com.mooc.backend.repository.PostRepository;
import com.mooc.backend.repository.SpotRepository;
import com.mooc.backend.service.rag.KnowledgeRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 混合搜索编排服务测试（change: ai-semantic-search，tasks 3.2 / design.md D2/D4）：
 * 双路编排与 RRF 融合、实体化（title/subtitle/url 来自当行数据、丢弃失效实体）、
 * types 过滤下推、向量腿异常 fail-open 纯关键词退化。全部用桩，零出网。
 */
class SearchServiceTest {

    private static final SearchProperties PROPS = new SearchProperties(20, true, 30);

    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final CityRepository cityRepository = mock(CityRepository.class);
    private final SpotRepository spotRepository = mock(SpotRepository.class);
    private final PostRepository postRepository = mock(PostRepository.class);
    private final SearchService service = new SearchService(retriever, cityRepository, spotRepository, postRepository, PROPS);

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(retriever, cityRepository, spotRepository, postRepository);
        when(retriever.search(any(), anyInt(), any())).thenReturn(List.of());
    }

    private static City city(String slug, String name, String nameZh) {
        return City.create(UUID.randomUUID(), name, nameZh, slug, null, "d", "spring", Instant.now());
    }

    private static Spot spot(String slug, String nameEn, String summaryEn) {
        return Spot.create(UUID.randomUUID(), slug, "中文名", nameEn, "hangzhou",
                com.mooc.backend.entity.SpotCategory.NATURE, List.of(), null, null, null, 30.0, 120.0,
                null, List.of(), summaryEn, null, null, null, null, null, null,
                null, false, false, SpotStatus.PUBLISHED, Instant.now());
    }

    private static Post post(String title, String content, PostStatus status) {
        Post p = Post.create(UUID.randomUUID(), title, content, null, List.of(), status, null, Instant.now());
        return p;
    }

    @Test
    void mergesBothLegsAndEnrichesFromCurrentRows() {
        when(retriever.search(eq("lake"), eq(20), any())).thenReturn(List.of(
                new Document("d1", "t", Map.of("type", "spot", "slug", "west-lake")),
                new Document("d2", "t", Map.of("type", "city", "slug", "hangzhou"))));
        when(spotRepository.searchByKeyword("lake", 10)).thenReturn(List.of(spot("lingyin", "Lingyin Temple", "s")));
        when(cityRepository.searchByKeyword("lake", 10)).thenReturn(List.of());
        when(postRepository.searchByKeyword("lake", 10)).thenReturn(List.of());

        when(spotRepository.findBySlugInAndDeletedFalse(any()))
                .thenReturn(List.of(spot("west-lake", "West Lake", "A famous lake. Very pretty."),
                        spot("lingyin", "Lingyin Temple", "s")));
        when(cityRepository.findBySlugAndDeletedFalse("hangzhou"))
                .thenReturn(Optional.of(city("hangzhou", "Hangzhou", "杭州")));

        SearchResponse response = service.search("lake", List.of(), 10);

        assertThat(response.getItems()).hasSize(3);
        // lingyin 与 west-lake 同为 1/61（各为某一路 rank1）→ tie-break 按 key 字典序 lingyin 第一；
        // hangzhou 单路 rank2（1/62）垫底
        assertThat(response.getItems().get(0).getType()).isEqualTo("spot");
        assertThat(response.getItems().get(0).getKey()).isEqualTo("lingyin");
        assertThat(response.getItems().get(1).getKey()).isEqualTo("west-lake");
        assertThat(response.getItems().get(1).getTitle()).isEqualTo("West Lake");
        assertThat(response.getItems().get(1).getSubtitle()).isEqualTo("A famous lake. Very pretty.");
        assertThat(response.getItems().get(1).getUrl()).isEqualTo("/spots/west-lake");
        assertThat(response.getItems().get(2).getKey()).isEqualTo("hangzhou");
        assertThat(response.getItems()).anySatisfy(i -> {
            assertThat(i.getType()).isEqualTo("city");
            assertThat(i.getTitle()).isEqualTo("Hangzhou");
            assertThat(i.getSubtitle()).isEqualTo("杭州");
            assertThat(i.getUrl()).isEqualTo("/cities/hangzhou");
        });
        assertThat(response.getTotal()).isEqualTo(3);
        assertThat(response.getQuery()).isEqualTo("lake");
    }

    @Test
    void vectorLegExceptionDegradesToKeywordOnly() {
        when(retriever.search(any(), anyInt(), any())).thenThrow(new IllegalStateException("milvus down"));
        when(spotRepository.searchByKeyword("lake", 10)).thenReturn(List.of(spot("west-lake", "West Lake", "s")));
        when(spotRepository.findBySlugInAndDeletedFalse(List.of("west-lake")))
                .thenReturn(List.of(spot("west-lake", "West Lake", "s")));

        SearchResponse response = service.search("lake", List.of(), 10);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getKey()).isEqualTo("west-lake");
    }

    @Test
    void dropsEntityMissingFromDatabase() {
        // 向量命中已下架实体（索引水位线延迟）→ 实体化时丢弃
        when(retriever.search(eq("lake"), eq(20), any())).thenReturn(List.of(
                new Document("d1", "t", Map.of("type", "spot", "slug", "deleted-spot"))));
        when(spotRepository.findBySlugInAndDeletedFalse(List.of("deleted-spot"))).thenReturn(List.of());

        SearchResponse response = service.search("lake", List.of(), 10);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotal()).isZero();
    }

    @Test
    void postEnrichmentDerivesSubtitleFromMarkdownFirstSentence() {
        Post live = post("Tea guide", "# Hangzhou\n\nLongjing tea is great. More text follows. Extra.", PostStatus.PUBLISHED);
        Post draft = post("Draft tea", "c", PostStatus.DRAFT);
        Post deleted = post("Deleted tea", "c", PostStatus.PUBLISHED);
        deleted.markDeleted();
        when(retriever.search(eq("tea"), eq(20), any())).thenReturn(List.of(
                new Document("d1", "t", Map.of("type", "post", "id", live.getId().toString())),
                new Document("d2", "t", Map.of("type", "post", "id", draft.getId().toString())),
                new Document("d3", "t", Map.of("type", "post", "id", deleted.getId().toString()))));
        when(postRepository.findAllById(any())).thenReturn(List.of(live, draft, deleted));

        SearchResponse response = service.search("tea", List.of("post"), 10);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getType()).isEqualTo("post");
        assertThat(response.getItems().get(0).getKey()).isEqualTo(live.getId().toString());
        assertThat(response.getItems().get(0).getTitle()).isEqualTo("Tea guide");
        assertThat(response.getItems().get(0).getSubtitle()).isEqualTo("Hangzhou Longjing tea is great.");
        assertThat(response.getItems().get(0).getUrl()).isEqualTo("/posts/" + live.getId());
    }

    @Test
    void typesSubsetPushesFilterExpressionAndSkipsOtherLegs() {
        when(retriever.search(any(), anyInt(), any())).thenReturn(List.of());
        when(cityRepository.searchByKeyword("lake", 10)).thenReturn(List.of());
        when(spotRepository.searchByKeyword("lake", 10)).thenReturn(List.of());

        service.search("lake", List.of("city", "spot"), 10);

        // 向量腿收到 type in ['city','spot'] 过滤
        verify(retriever).search(eq("lake"), eq(20), any(org.springframework.ai.vectorstore.filter.Filter.Expression.class));
        verify(cityRepository).searchByKeyword("lake", 10);
        verify(spotRepository).searchByKeyword("lake", 10);
        verify(postRepository, org.mockito.Mockito.never()).searchByKeyword(any(), anyInt());
    }

    @Test
    void allTypesQueriesVectorLegWithoutFilter() {
        when(retriever.search(any(), anyInt(), any())).thenReturn(List.of());

        service.search("lake", List.of(), 10);

        verify(retriever).search(eq("lake"), eq(20), eq(null));
    }

    @Test
    void limitCapsFinalItems() {
        when(retriever.search(any(), anyInt(), any())).thenReturn(List.of());
        when(cityRepository.searchByKeyword("hang", 2)).thenReturn(List.of(
                city("a", "A", "甲"), city("b", "B", "乙")));
        when(cityRepository.findBySlugAndDeletedFalse("a")).thenReturn(Optional.of(city("a", "A", "甲")));
        when(cityRepository.findBySlugAndDeletedFalse("b")).thenReturn(Optional.of(city("b", "B", "乙")));

        SearchResponse response = service.search("hang", List.of("city"), 2);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getTotal()).isEqualTo(2);
    }
}
