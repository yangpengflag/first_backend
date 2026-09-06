package com.mooc.backend.travel.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.mooc.backend.travel.client.CurrentWeather;
import com.mooc.backend.travel.client.ForecastBucket;

import java.time.Instant;
import java.util.List;

/**
 * 天气缓存的 JSON 编解码（change: add-travel-services，task 4.4）。
 *
 * <p>照 {@code ExchangeRateCacheJson} 的既有做法：独立 plain ObjectMapper，缓存字节格式与
 * HTTP 主 mapper 解耦；装 {@code JavaTimeModule}（有 {@code Instant}）并关
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}（TTL 24h，跨发布期的旧字节当 miss 而非异常）。
 * 内层 {@code CurrentWeather} / {@code ForecastBucket} 自带 snake_case {@code @JsonProperty}，
 * {@code redis-cli GET} 直接可读。
 */
final class WeatherCacheJson {

    private WeatherCacheJson() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // ISO-8601 文本而非 epoch 数字：缓存内容要能人眼排查
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 缓存里的形状。刻意不存 {@code stale}——那是读取时刻相对 {@code fetchedAt} 算出来的
     * 派生值，存进缓存会随时间腐烂（同 {@code ExchangeRateCacheJson} 的注释）。
     * 也没有快照版本——天气速朽，24h 前的预报无存储收益（design §2）。
     */
    record Payload(@JsonProperty("fetched_at") Instant fetchedAt,
                   @JsonProperty("current") CurrentWeather current,
                   @JsonProperty("forecast") List<ForecastBucket> forecast) {
    }
}
