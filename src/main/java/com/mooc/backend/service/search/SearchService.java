package com.mooc.backend.service.search;

import com.mooc.backend.config.SearchProperties;
import com.mooc.backend.dto.response.SearchItemResponse;
import com.mooc.backend.dto.response.SearchResponse;
import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.Spot;
import com.mooc.backend.repository.CityRepository;
import com.mooc.backend.repository.PostRepository;
import com.mooc.backend.repository.SpotRepository;
import com.mooc.backend.service.MarkdownSummary;
import com.mooc.backend.service.rag.KnowledgeRetriever;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 混合搜索编排（change: ai-semantic-search，design.md D1–D5）。
 *
 * <p>双路检索 → RRF 融合 → {@code (type, key)} 去重 → 按实体键查库实体化（丢弃不存在 /
 * 软删 / 非 PUBLISHED 的过期条目——<b>以 MySQL 当行数据为最终准绳</b>）→ 生成统一条目。
 * 向量腿任何异常按 fail-open 处理（退纯关键词，恒不抛到 controller）。
 *
 * <p>展示字段全部来自 MySQL 当行数据而非索引 metadata：向量索引有水位线延迟，
 * 行不存在即丢弃。subtitle 策略（tasks 3.3）：city→中文名（与 title=英文名不重复）、
 * spot→英文摘要截断 120、post→{@link MarkdownSummary} 派生摘要首句截断 120。
 */
@Service
public class SearchService {

    /** 与 KnowledgeDocumentMapper metadata 的 type 取值一致。 */
    private static final Set<String> ALL_TYPES = Set.of("city", "spot", "post");

    private static final int SUBTITLE_MAX_CHARS = 120;

    private final KnowledgeRetriever retriever;
    private final CityRepository cityRepository;
    private final SpotRepository spotRepository;
    private final PostRepository postRepository;
    private final SearchProperties props;

    public SearchService(KnowledgeRetriever retriever, CityRepository cityRepository,
                         SpotRepository spotRepository, PostRepository postRepository,
                         SearchProperties props) {
        this.retriever = retriever;
        this.cityRepository = cityRepository;
        this.spotRepository = spotRepository;
        this.postRepository = postRepository;
        this.props = props;
    }

    /**
     * @param q     原始查询（controller 已校验非空白）
     * @param types 允许的类型子集（空 / 含全部三类 = 不限；非法取值由 controller 校验）
     * @param limit 最终条目上限（controller 已钳制 [1,20]）
     */
    public SearchResponse search(String q, List<String> types, int limit) {
        List<String> allowed = normalizeTypes(types);

        // 向量腿（fail-open：不可用 / 异常 → 空列表，自然退化为纯关键词）
        List<RrfFusion.Candidate> vectorRanked = searchVectorLeg(q, allowed);

        // 关键词腿（本地 MySQL，恒可用）
        List<RrfFusion.Candidate> keywordRanked = searchKeywordLeg(q, allowed, limit);

        // RRF 融合 + 截取前 limit
        List<RrfFusion.Scored> top = RrfFusion.fuse(vectorRanked, keywordRanked).stream()
                .limit(limit)
                .toList();

        return SearchResponse.of(enrich(top), q);
    }

    private List<String> normalizeTypes(List<String> types) {
        if (types == null || types.isEmpty() || types.containsAll(ALL_TYPES)) {
            return List.copyOf(ALL_TYPES);
        }
        return types.stream().distinct().filter(ALL_TYPES::contains).toList();
    }

    /** 向量腿：metadata {@code type} 下推到 Milvus filterExpression；全类型时不过滤。 */
    private List<RrfFusion.Candidate> searchVectorLeg(String q, List<String> allowed) {
        try {
            Filter.Expression filter = null;
            if (!allowed.containsAll(ALL_TYPES)) {
                filter = new FilterExpressionBuilder().in("type", allowed).build();
            }
            List<Document> docs = retriever.search(q, props.vectorTopK(), filter);
            List<RrfFusion.Candidate> out = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                candidateFromMetadata(doc).ifPresent(out::add);
            }
            return out;
        } catch (RuntimeException e) {
            // 双保险：KnowledgeRetriever 自身 fail-open，此处兜底防实现回归
            return List.of();
        }
    }

    /** 从 Document metadata 提取 (type, 实体键)：city/spot 用 slug，post 用 id。缺元数据的块跳过。 */
    private Optional<RrfFusion.Candidate> candidateFromMetadata(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        Object type = meta.get("type");
        if (!(type instanceof String t) || !ALL_TYPES.contains(t)) {
            return Optional.empty();
        }
        String keyField = "post".equals(t) ? "id" : "slug";
        Object key = meta.get(keyField);
        if (!(key instanceof String k) || k.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RrfFusion.Candidate(t, k));
    }

    private List<RrfFusion.Candidate> searchKeywordLeg(String q, List<String> allowed, int limit) {
        List<RrfFusion.Candidate> out = new ArrayList<>();
        if (allowed.contains("city")) {
            cityRepository.searchByKeyword(q, limit).stream()
                    .map(c -> new RrfFusion.Candidate("city", c.getSlug()))
                    .forEach(out::add);
        }
        if (allowed.contains("spot")) {
            spotRepository.searchByKeyword(q, limit).stream()
                    .map(s -> new RrfFusion.Candidate("spot", s.getSlug()))
                    .forEach(out::add);
        }
        if (allowed.contains("post")) {
            postRepository.searchByKeyword(q, limit).stream()
                    .map(p -> new RrfFusion.Candidate("post", p.getId().toString()))
                    .forEach(out::add);
        }
        return out;
    }

    /** 实体化：按当行数据生成展示字段；行缺失 / 软删 / 非 PUBLISHED（索引延迟）的条目丢弃。 */
    private List<SearchItemResponse> enrich(List<RrfFusion.Scored> top) {
        Map<String, City> cities = new HashMap<>();
        Map<String, Spot> spots = new HashMap<>();
        Map<String, Post> posts = new HashMap<>();

        Set<String> citySlugs = new LinkedHashSet<>();
        Set<String> spotSlugs = new LinkedHashSet<>();
        Set<UUID> postIds = new LinkedHashSet<>();
        for (RrfFusion.Scored s : top) {
            RrfFusion.Candidate c = s.candidate();
            switch (c.type()) {
                case "city" -> citySlugs.add(c.key());
                case "spot" -> spotSlugs.add(c.key());
                case "post" -> parseUuid(c.key()).ifPresent(postIds::add);
                default -> {
                }
            }
        }

        for (String slug : citySlugs) {
            cityRepository.findBySlugAndDeletedFalse(slug).ifPresent(c -> cities.put(slug, c));
        }
        if (!spotSlugs.isEmpty()) {
            spotRepository.findBySlugInAndDeletedFalse(List.copyOf(spotSlugs))
                    .forEach(s -> spots.put(s.getSlug(), s));
        }
        if (!postIds.isEmpty()) {
            postRepository.findAllById(List.copyOf(postIds)).stream()
                    .filter(p -> !p.isDeleted() && p.isPublished())
                    .forEach(p -> posts.put(p.getId().toString(), p));
        }

        List<SearchItemResponse> items = new ArrayList<>(top.size());
        for (RrfFusion.Scored s : top) {
            RrfFusion.Candidate c = s.candidate();
            switch (c.type()) {
                case "city" -> {
                    City city = cities.get(c.key());
                    if (city != null) {
                        items.add(new SearchItemResponse("city", c.key(), city.getName(),
                                city.getNameZh(), "/cities/" + city.getSlug(), s.score()));
                    }
                }
                case "spot" -> {
                    Spot spot = spots.get(c.key());
                    if (spot != null) {
                        items.add(new SearchItemResponse("spot", c.key(), spot.getNameEn(),
                                truncate(spot.getSummaryEn()), "/spots/" + spot.getSlug(), s.score()));
                    }
                }
                case "post" -> {
                    Post post = posts.get(c.key());
                    if (post != null) {
                        items.add(new SearchItemResponse("post", c.key(), post.getTitle(),
                                firstSentenceSubtitle(post.getContent()),
                                "/posts/" + post.getId(), s.score()));
                    }
                }
                default -> {
                }
            }
        }
        return items;
    }

    /** post subtitle：MarkdownSummary 派生纯文本 → 首句 → 截断（勿对原始 markdown 自行截取）。 */
    private String firstSentenceSubtitle(String content) {
        String plain = MarkdownSummary.derive(content);
        if (plain.isBlank()) {
            return null;
        }
        String sentence = plain;
        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？') {
                sentence = plain.substring(0, i + 1);
                break;
            }
        }
        return truncate(sentence);
    }

    private String truncate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= SUBTITLE_MAX_CHARS ? trimmed : trimmed.substring(0, SUBTITLE_MAX_CHARS);
    }

    private Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
