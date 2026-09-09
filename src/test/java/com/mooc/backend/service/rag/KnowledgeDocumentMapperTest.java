package com.mooc.backend.service.rag;

import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.PostStatus;
import com.mooc.backend.entity.Spot;
import com.mooc.backend.entity.SpotCategory;
import com.mooc.backend.entity.SpotStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体 → 检索文档映射契约（change: ai-rag，tasks 3.2 / design.md D4；id 形态自
 * ai-rag-milvus 起为 md5({@code type:key:seq}) 32 位 hex——Milvus doc_id VarChar(36) 约束）：
 * 三类来源元数据、spot 双语分块（en 含 facts、zh 不含重复 facts）、post 长文多块 seq、文档 id 幂等。
 */
class KnowledgeDocumentMapperTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static String md5(String logicalKey) {
        return DigestUtils.md5DigestAsHex(logicalKey.getBytes(StandardCharsets.UTF_8));
    }

    private static City city() {
        return City.create(UUID.randomUUID(), "Chengdu", "成都", "chengdu",
                "cover.jpg", "A laid-back city of pandas and spicy food.", "Spring & Autumn", NOW);
    }

    private static Spot spot() {
        return Spot.create(UUID.randomUUID(), "chengdu-giant-panda-base", "成都大熊猫繁育研究基地",
                "Giant Panda Base", "chengdu", SpotCategory.NATURE,
                List.of("pandas", "nature"), "A", "North of Chengdu, China", "成都市北郊",
                30.73, 104.14, "cover.jpg", List.of(),
                "Home of giant pandas.", "大熊猫的家。",
                "Spacious reserve with giant pandas, red pandas and bamboo groves. Best visited early morning.",
                "宽阔的保护区内生活着大熊猫、小熊猫和竹林。建议清晨前往。",
                "07:30-18:00", "CNY 55", "3-4 hours", null, true, true, SpotStatus.PUBLISHED, NOW);
    }

    private static Post post(String content) {
        return Post.create(UUID.randomUUID(), "Two weeks on the Silk Road",
                content, "cover.jpg", List.of("silk-road", "history"),
                PostStatus.PUBLISHED, "xi-an", NOW);
    }

    @Test
    void cityMapsSingleDocumentWithMetadata() {
        City c = city();
        List<Document> docs = KnowledgeDocumentMapper.mapCity(c, 1200);

        assertThat(docs).hasSize(1);
        Document doc = docs.get(0);
        assertThat(doc.getId()).isEqualTo(md5("city:chengdu:0")).hasSize(32);
        assertThat(doc.getText()).contains("Chengdu").contains("pandas");
        assertThat(doc.getMetadata())
                .containsEntry("type", "city")
                .containsEntry("slug", "chengdu")
                .containsEntry("name", "Chengdu")
                .containsEntry("url", "/cities/chengdu");
    }

    @Test
    void spotMapsBilingualChunksWithContinuousSeq() {
        Spot s = spot();
        List<Document> docs = KnowledgeDocumentMapper.mapSpot(s, 1200);

        // 双语各至少一块；id 连续且 language 标记正确
        assertThat(docs).hasSizeGreaterThanOrEqualTo(2);
        Document firstEn = docs.get(0);
        Document firstZh = docs.stream().filter(d -> "zh".equals(d.getMetadata().get("language"))).findFirst().orElseThrow();
        assertThat(firstEn.getMetadata()).containsEntry("type", "spot")
                .containsEntry("slug", "chengdu-giant-panda-base")
                .containsEntry("url", "/spots/chengdu-giant-panda-base")
                .containsEntry("language", "en");
        // id 幂等：md5(spot:slug:0..n)（32 hex，满足 Milvus VarChar(36)）
        for (int i = 0; i < docs.size(); i++) {
            assertThat(docs.get(i).getId()).isEqualTo(md5("spot:chengdu-giant-panda-base:" + i));
        }
        // en 块含 facts（开放时间/门票），zh 块只含中文摘要与地址（不含重复英文 facts）
        assertThat(firstEn.getText()).contains("Opening hours: 07:30-18:00").contains("Tickets: CNY 55");
        assertThat(firstEn.getText()).contains("Giant Panda Base");
        assertThat(firstZh.getText()).contains("大熊猫的家");
        assertThat(firstZh.getMetadata().get("language")).isEqualTo("zh");
        int enIdx = docs.indexOf(firstEn);
        assertThat(docs.get(enIdx).getMetadata().get("language")).isEqualTo("en");
        assertThat(docs.stream().filter(d -> "zh".equals(d.getMetadata().get("language"))).findFirst()
                .map(docs::indexOf).orElseThrow()).isEqualTo(enIdx + 1);
    }

    @Test
    void longPostSplitsWithSeqIdsAndSharedMetadata() {
        Post p = post(("# Day 1\n" + "Visit the Terracotta Army. ".repeat(80) + "\n\n"
                + "## Day 2\n" + "Hike the city walls. ".repeat(120)).trim());
        List<Document> docs = KnowledgeDocumentMapper.mapPost(p, 300);

        assertThat(docs).hasSizeGreaterThan(1);
        for (int i = 0; i < docs.size(); i++) {
            assertThat(docs.get(i).getId()).isEqualTo(md5("post:" + p.getId() + ":" + i));
            assertThat(docs.get(i).getMetadata())
                    .containsEntry("type", "post")
                    .containsEntry("title", "Two weeks on the Silk Road")
                    .containsEntry("url", "/posts/" + p.getId());
        }
    }

    @Test
    void shortPostYieldsSingleDocument() {
        Post p = post("A short and concise guide. ".repeat(10).trim());
        assertThat(KnowledgeDocumentMapper.mapPost(p, 1200)).hasSize(1);
    }
}
