package com.mooc.backend.places.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.places.api.SpotSummary;

/**
 * 排行榜缓存专用 JSON 序列化器（change: add-spot-ranking-redis-cache）。
 *
 * <p>独立 plain ObjectMapper——不复用 / 不 copy HTTP 主 mapper：{@code SpotSummary} 全字段由
 * {@code @JsonProperty} / {@code @JsonInclude(ALWAYS)} 注解驱动、无日期与自定义序列化器，
 * plain mapper 输出与 HTTP 逻辑等价；把缓存字节格式与 HTTP 主 mapper 配置解耦，主 mapper 未来调整
 * （命名策略 / modules / inclusion）不会让已存缓存悄悄脱节。
 *
 * <p>mixin 忽略 {@code request_id}：缓存字节不携带关联 ID；反序列化命中时对象经
 * {@code BaseResponse} 构造器按当前线程 MDC 重建 {@code request_id}，不回放写缓存请求的 ID。
 */
public final class SpotSummaryCacheJson {

    private SpotSummaryCacheJson() {
    }

    /** mixin 目标：仅作用于缓存 mapper，不影响 HTTP 层出网序列化。 */
    @JsonIgnoreProperties("request_id")
    private interface IgnoreRequestIdMixin {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 容忍缓存字节格式小幅演进（未来新增字段不会让旧缓存读取失败）
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addMixIn(SpotSummary.class, IgnoreRequestIdMixin.class);

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
