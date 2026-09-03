package com.mooc.backend.places.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;
import com.mooc.backend.places.service.SpotSummaryCacheJson;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排行榜缓存 DTO 序列化 round-trip 护栏（change: add-spot-ranking-redis-cache）。
 *
 * <p>断言：缓存 mapper（忽略 {@code request_id}）序列化后再反序列化 {@code List<SpotSummary>}
 * 字段一致；序列化字节不含 {@code request_id}；null / 空 list 往返不丢；命中对象经
 * {@code BaseResponse} 构造器携带<b>当次</b>请求的 {@code request_id}（不回放写缓存请求的 ID）。
 */
class SpotSummaryCacheSerializationTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    private final ObjectMapper mapper = SpotSummaryCacheJson.mapper();

    @BeforeEach
    void setUp() {
        MDC.put("requestId", "req-writer");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private static Spot spot(String slug, String nameZh, String nameEn, Double rating, long views,
                             List<String> tags, List<String> gallery) {
        return Spot.create(UUID.randomUUID(), slug, nameZh, nameEn, "hangzhou", SpotCategory.NATURE,
                tags, "5A", "West Lake addr", "西湖地址", 30.25, 120.15, "https://img.example.com/c.jpg",
                gallery, "summary en", "summary zh", "desc en", "desc zh", "09:00-17:00", "free", "2h",
                rating, true, false, SpotStatus.PUBLISHED, NOW);
    }

    @Test
    void roundTripPreservesAllFieldsAndOmitsRequestId() throws Exception {
        MDC.put("requestId", "req-writer");
        List<SpotSummary> summaries = List.of(
                SpotSummary.from(spot("hz-west-lake", "西湖", "West Lake", 4.7, 300,
                        List.of("lake", "nature"), List.of("g1", "g2"))),
                SpotSummary.from(spot("hz-lingyin", "灵隐寺", "Lingyin Temple", null, 50,
                        List.of("temple"), List.of())));
        // from() 会带 request_id（读写 MDC）——上面 MDC 已置为 req-writer

        String json = mapper.writeValueAsString(summaries);
        assertThat(json).doesNotContain("request_id").doesNotContain("req-writer");

        JsonNode expected = mapper.readTree(json);
        List<SpotSummary> back = mapper.readValue(json, new TypeReference<List<SpotSummary>>() { });
        String json2 = mapper.writeValueAsString(back);
        assertThat(mapper.readTree(json2)).isEqualTo(expected);
        assertThat(back).hasSize(2);
    }

    @Test
    void nullAndEmptyCollectionsSurviveRoundTrip() throws Exception {
        Spot empty = spot("hz-empty", null, "Empty", null, 0, List.of(), List.of());
        // 直接置空可变集合以模拟最少字段的极端值
        List<SpotSummary> summaries = List.of(SpotSummary.from(empty));

        String json = mapper.writeValueAsString(summaries);
        List<SpotSummary> back = mapper.readValue(json, new TypeReference<List<SpotSummary>>() { });

        SpotSummary first = back.get(0);
        assertThat(first.getSlug()).isEqualTo("hz-empty");
        assertThat(first.getNameZh()).isNull();
        assertThat(first.getRating()).isNull();
        assertThat(first.getTags()).isEmpty();
        assertThat(first.getGalleryUrls()).isEmpty();
        assertThat(first.getViewCount()).isZero();
    }

    @Test
    void deserializedItemCarriesCurrentRequestIdNotWriter() throws Exception {
        // 写缓存请求：req-writer
        List<SpotSummary> summaries = List.of(
                SpotSummary.from(spot("hz-west-lake", "西湖", "West Lake", 4.7, 300, List.of(), List.of())));
        String json = mapper.writeValueAsString(summaries);
        assertThat(json).doesNotContain("request_id");

        // 命中请求：req-reader —— 重建 DTO 应带当次请求 ID
        MDC.put("requestId", "req-reader");
        List<SpotSummary> back = mapper.readValue(json, new TypeReference<List<SpotSummary>>() { });
        assertThat(back.get(0).getRequestId()).isEqualTo("req-reader");
    }
}
