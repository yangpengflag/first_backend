package com.mooc.backend.travel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;
import com.mooc.backend.travel.service.ExchangeRateView;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * `GET /api/travel/rates` 响应（change: add-travel-services，task 5.2）。
 *
 * <p>可空字段（{@code rates} / {@code as_of} / {@code fetched_at}；{@code stale} / {@code base}
 * 恒非空）：可空性由 {@link TravelOpenApiCustomizer} 在 OpenAPI 文档层包裹成
 * {@code oneOf: [原 schema, {type:"null"}]}（springdoc 2.8.8 在 3.1 模式下会丢弃
 * {@code @Schema(nullable = true)}，无法靠注解直接表达）——生成的 TS 因此含 {@code | null}，
 * 前端 {@code strict: true} 才接得住降级返回的 {@code null}（design §6）。
 *
 * <p><b>可空字段序列化为显式 {@code null}</b>（不用 {@code NON_NULL} 抹掉）：降级形状
 * {@code {"base":"CNY","rates":null,...}} 与「字段不存在」在前端解构下不是一回事，契约
 * （design §6）明确给出带 null 的形状。
 */
public class TravelRatesResponse extends BaseResponse {

    @JsonProperty("base")
    private final String base;

    @JsonProperty("as_of")
    @Schema(description = "上游牌价所属日期（ECB 参考价日期）。无数据时为 null")
    private final LocalDate asOf;

    @JsonProperty("fetched_at")
    @Schema(description = "本系统成功拉取该数据的时刻。无数据时为 null")
    private final Instant fetchedAt;

    @JsonProperty("stale")
    @Schema(description = "数据仍可用但已超 soft TTL（26h）。无数据时恒为 false——是不存在，而非存在且旧")
    private final boolean stale;

    @JsonProperty("rates")
    @Schema(description = "汇率表，1 base = value target。停用 / 冷启动 / 超 hard TTL 时为 null")
    private final Map<String, Double> rates;

    private TravelRatesResponse(String base, LocalDate asOf, Instant fetchedAt, boolean stale,
                                Map<String, Double> rates) {
        super();
        this.base = base;
        this.asOf = asOf;
        this.fetchedAt = fetchedAt;
        this.stale = stale;
        this.rates = rates;
    }

    /** 降级（view 为 null）仍带配置中的基准币种——它是常量事实，与上游可用性无关。 */
    public static TravelRatesResponse from(ExchangeRateView view, String configuredBase) {
        if (view == null) {
            return new TravelRatesResponse(configuredBase, null, null, false, null);
        }
        return new TravelRatesResponse(view.base() != null ? view.base() : configuredBase,
                view.asOf(), view.fetchedAt(), view.stale(), view.rates());
    }
}
