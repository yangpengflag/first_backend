package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 每轮检索器契约（change: ai-rag，tasks 5.1 / design.md D6）：
 * 空白查询/未就绪/向量库异常均返回空且不抛；命中时转发 top-k 检索结果。
 */
class KnowledgeRetrieverTest {

    private static final AiRagProperties PROPS = new AiRagProperties(
            true, "localhost", 19530, 1024, "wanderchina-knowledge", 4, 0.0, 1200,
            "0 0 5 * * *", Duration.ofMinutes(5));

    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final KnowledgeRetriever retriever = new KnowledgeRetriever(store, PROPS);

    @Test
    void blankQueryReturnsEmptyWithoutTouchingStore() {
        assertThat(retriever.search("   ")).isEmpty();
        assertThat(retriever.search(null)).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void storeNotReadyReturnsEmpty() {
        when(store.store()).thenReturn(Optional.empty());

        assertThat(retriever.search("anything")).isEmpty();
    }

    @Test
    void hitsAreForwardedFromVectorStore() {
        VectorStore vs = mock(VectorStore.class);
        when(store.store()).thenReturn(Optional.of(vs));
        Document hit = new Document("city:chengdu:0", "Chengdu text",
                Map.of("type", "city", "name", "Chengdu"));
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        List<Document> result = retriever.search("what to see in chengdu");

        assertThat(result).containsExactly(hit);
        verify(vs).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void vectorStoreFailureReturnsEmptyWithoutPropagating() {
        VectorStore vs = mock(VectorStore.class);
        when(store.store()).thenReturn(Optional.of(vs));
        when(vs.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("milvus down"));

        assertThat(retriever.search("hello")).isEmpty();
    }

}
