package com.mooc.backend.travel.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 汇率缓存的 JSON 编解码（change: add-travel-services，task 3.4）。
 *
 * <p>独立 plain ObjectMapper，照 {@code SpotSummaryCacheJson} 的既有做法：把缓存字节格式与
 * HTTP 主 mapper 解耦，主 mapper 将来调整命名策略 / modules / inclusion 不会让已存缓存悄悄脱节。
 *
 * <p>与 {@code SpotSummaryCacheJson} 的一处不同：<b>这里必须显式装 {@code JavaTimeModule}</b>。
 * 那边的 {@code SpotSummary} 无日期字段，plain mapper 够用；这边有 {@code Instant} 与
 * {@code LocalDate}，不装则序列化成时间戳数字数组、反序列化直接失败。
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} 关掉：缓存里可能躺着上一版格式的字节（TTL 长达 7d，
 * 一次发布覆盖不了它们），多出的字段应当被忽略而不是让读取抛异常退化成 miss。
 */
final class ExchangeRateCacheJson {

    private ExchangeRateCacheJson() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // ISO-8601 文本而非 epoch 数字：缓存内容要能人眼排查（redis-cli GET 直接可读）
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 缓存里的形状。<b>刻意与出网 DTO 分开</b>：这里存的是「数据本身」，不存 {@code stale}——
     * 那是读取时刻相对 {@code fetchedAt} 算出来的派生值，存进缓存就会随时间腐烂
     * （写入时算的 false 会在 26h 后依然是 false）。
     */
    record Payload(@JsonProperty("base") String base,
                   @JsonProperty("upstream_date") LocalDate upstreamDate,
                   @JsonProperty("fetched_at") Instant fetchedAt,
                   @JsonProperty("rates") Map<String, Double> rates) {
    }
}
