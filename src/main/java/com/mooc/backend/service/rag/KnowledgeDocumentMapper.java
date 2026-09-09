package com.mooc.backend.service.rag;

import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.Spot;
import org.springframework.ai.document.Document;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 站内实体 → 检索文档映射（change: ai-rag，tasks 3.2 / design.md D4）。
 *
 * <p>三种来源各自生成带元数据的 {@link Document} 列表：city 单语言整文；spot 每语言一块
 * （英文含 facts：开放时间/门票/停留时长，中文仅摘要+详情+地址，避免事实双份冗余）；
 * post 标题/标签+正文 markdown。
 *
 * <p>文档 id（change: ai-rag-milvus）：逻辑键 {@code city:{slug}:{seq}} / {@code spot:{slug}:{seq}}
 * / {@code post:{uuid}:{seq}}（seq 为切块序号）经 md5 映射为 32 位 hex——Milvus 主键
 * {@code doc_id} 为 VarChar(36)，{@code post:{uuid}:{seq}} 形态达 43 字符会被 SDK 客户端
 * 校验拒绝（ParamUtils，spike 实证）；md5 输入不变，幂等语义保留，可读性由 metadata
 * 的 type/slug/url 承担。
 *
 * <p>内容超长由 {@link KnowledgeDocumentChunker} 切块（入参 {@code chunkMaxChars}）；本类不做
 * PUBLISHED/未删过滤——过滤由数据源查询层保证（repository 已限定），保持映射纯函数可测。
 */
public final class KnowledgeDocumentMapper {

    private KnowledgeDocumentMapper() {
    }

    public static List<Document> mapCity(City city, int chunkMaxChars) {
        StringJoiner text = new StringJoiner("\n");
        text.add(city.getName() + (isBlank(city.getNameZh()) ? "" : " (" + city.getNameZh() + ")"));
        if (!isBlank(city.getBestSeason())) {
            text.add("Best season: " + city.getBestSeason());
        }
        if (!isBlank(city.getDescription())) {
            text.add(city.getDescription());
        }
        Map<String, Object> meta = Map.of(
                "type", "city",
                "slug", city.getSlug(),
                "name", city.getName(),
                "language", "en",
                "url", "/cities/" + city.getSlug());
        return chunked("city", city.getSlug(), text.toString(), meta, chunkMaxChars);
    }

    public static List<Document> mapSpot(Spot spot, int chunkMaxChars) {
        String headEn = spot.getNameEn() + (isBlank(spot.getNameZh()) ? "" : " (" + spot.getNameZh() + ")");
        Map<String, Object> base = Map.of(
                "type", "spot",
                "slug", spot.getSlug(),
                "nameEn", spot.getNameEn(),
                "nameZh", spot.getNameZh(),
                "citySlug", spot.getCitySlug(),
                "url", "/spots/" + spot.getSlug());

        String intro = "Category: " + spot.getCategory().name().toLowerCase()
                + (spot.getTags().isEmpty() ? "" : " · Tags: " + String.join(", ", spot.getTags()));

        String enText = headEn + "\n" + intro + "\n\n" + joinedBlock(
                spot.getSummaryEn(), spot.getDescriptionEn(),
                factsEn(spot));
        List<Document> enDocs = chunked("spot", spot.getSlug(), enText,
                withLang(base, "en"), chunkMaxChars);

        String zhText = spot.getNameZh() + "\n\n" + joinedBlock(
                spot.getSummaryZh(), spot.getDescriptionZh(), spot.getAddressZh());
        List<Document> zhDocs = chunked("spot", spot.getSlug(), zhText,
                withLang(base, "zh"), chunkMaxChars);
        List<Document> out = new ArrayList<>(enDocs);
        out.addAll(zhDocs);
        return reindex(out, "spot", spot.getSlug());
    }

    public static List<Document> mapPost(Post post, int chunkMaxChars) {
        String uuid = post.getId().toString();
        Map<String, Object> meta = Map.of(
                "type", "post",
                "id", uuid,
                "title", post.getTitle(),
                "url", "/posts/" + uuid);
        StringJoiner text = new StringJoiner("\n");
        text.add("# " + post.getTitle());
        if (!post.getTags().isEmpty()) {
            text.add("Tags: " + String.join(", ", post.getTags()));
        }
        text.add(post.getContent());
        return chunked("post", uuid, text.toString(), meta, chunkMaxChars);
    }

    /** 英文 facts 块（开放时间/门票/停留时长/英文地址），与中文块避免重复。 */
    private static String factsEn(Spot spot) {
        StringJoiner facts = new StringJoiner("\n");
        if (!isBlank(spot.getAddressEn())) {
            facts.add("Address: " + spot.getAddressEn());
        }
        if (!isBlank(spot.getOpeningHours())) {
            facts.add("Opening hours: " + spot.getOpeningHours());
        }
        if (!isBlank(spot.getTicketInfo())) {
            facts.add("Tickets: " + spot.getTicketInfo());
        }
        if (!isBlank(spot.getVisitDuration())) {
            facts.add("Suggested duration: " + spot.getVisitDuration());
        }
        return facts.toString();
    }

    private static String joinedBlock(String... parts) {
        StringJoiner j = new StringJoiner("\n\n");
        for (String part : parts) {
            if (!isBlank(part)) {
                j.add(part);
            }
        }
        return j.toString();
    }

    private static Map<String, Object> withLang(Map<String, Object> meta, String lang) {
        Map<String, Object> copy = new java.util.HashMap<>(meta);
        copy.put("language", lang);
        return copy;
    }

    /** Milvus doc_id VarChar(36) 约束下的安全 id：md5(逻辑键) = 32 位 hex，确定性幂等。 */
    private static String docId(String logicalKey) {
        return DigestUtils.md5DigestAsHex(logicalKey.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Document> chunked(String type, String key, String text,
                                          Map<String, Object> metadata, int chunkMaxChars) {
        List<String> chunks = KnowledgeDocumentChunker.chunk(text, chunkMaxChars);
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            docs.add(new Document(docId(type + ":" + key + ":" + i), chunks.get(i), metadata));
        }
        return docs;
    }

    /** 统一重排 seq（spot 双语后 idx 全局连续，避免 en/zh 各自从 0 撞 id）。 */
    private static List<Document> reindex(List<Document> docs, String type, String key) {
        List<Document> out = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            out.add(new Document(docId(type + ":" + key + ":" + i),
                    docs.get(i).getText(), docs.get(i).getMetadata()));
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
