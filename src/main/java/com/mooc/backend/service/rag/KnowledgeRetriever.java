package com.mooc.backend.service.rag;

import com.mooc.backend.config.AiRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 每轮检索器（change: ai-rag，tasks 5.1 / design.md D6）。
 *
 * <p>以用户问题查询向量库取 top-k 命中。任何不可用（store 未就绪 / 检索异常）都静默返回空，
 * 不抛到对话链路——上层据此决定是否注入 knowledge section。检索失败告警留痕（不含 query 全文外泄，
 * 仅告警消息级别），由 {@link AiChatService} 每轮调用。
 *
 * <p>change: ai-semantic-search（tasks 2.1）：新增带 {@code topK} 与类型过滤的重载供混合搜索
 * 向量腿使用；原 {@link #search(String)} 委托重载、语义不变（topK / threshold 取 props），
 * ai-chat 注入链路零改动。
 */
@Component
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    private final KnowledgeStore knowledgeStore;
    private final AiRagProperties props;

    public KnowledgeRetriever(KnowledgeStore knowledgeStore, AiRagProperties props) {
        this.knowledgeStore = knowledgeStore;
        this.props = props;
    }

    /** 检索本轮问题最相关文档；不可用 / 异常 / 空白查询均返回空列表。 */
    public List<Document> search(String query) {
        return search(query, props.topK(), null);
    }

    /**
     * 带显式 {@code topK} 与 metadata 过滤的检索重载（fail-open 语义与单参版本一致）：
     * 不可用 / 异常 / 空白查询均返回空列表，异常仅告警留痕不外抛。
     *
     * @param filter metadata 过滤表达式（如 {@code type in ['spot','city']}），null 表示不过滤
     */
    public List<Document> search(String query, int topK, Filter.Expression filter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Optional<VectorStore> maybe = knowledgeStore.store();
        if (maybe.isEmpty()) {
            return List.of();
        }
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(props.minScore());
            if (filter != null) {
                builder.filterExpression(filter);
            }
            return maybe.get().similaritySearch(builder.build());
        } catch (RuntimeException e) {
            log.warn("Knowledge retrieval failed, answering without knowledge: {}",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return List.of();
        }
    }
}
