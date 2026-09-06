package com.mooc.backend.travel.client;

import java.time.LocalDate;
import java.util.Map;

/**
 * 一次成功的汇率拉取结果（change: add-travel-services，task 3.2）。
 *
 * <p>是客户端与服务层之间的内部载体，<b>不是 HTTP 响应 DTO</b>：不带 {@code @JsonProperty}、
 * 不继承 {@code BaseResponse}。出网形状由 {@code travel/api} 下的响应 DTO 负责。
 *
 * <p>不含 {@code fetchedAt}：那是「我们何时拿到」，由服务层用自己的 {@code Clock} 决定，
 * 让客户端顺手 {@code Instant.now()} 会把一个不可测的时钟塞进出网路径。
 *
 * @param base         基准币种，取自上游响应而非我们的请求参数（对得上才说明拿到的是想要的表）
 * @param upstreamDate 上游 {@code date}，即这份牌价所属日期（= 对外的 {@code as_of}）。
 *                     <b>与 {@code fetchedAt} 会合法地不相等</b>：ECB 周末与假日不发布，
 *                     周日刷新会成功拿到周五的牌价。TTL 判定一律用 {@code fetchedAt}，
 *                     展示日期一律用本字段。
 * @param rates        汇率表，{@code 1 base = value target}。保证非空（空表在客户端就当失败）。
 */
public record ExchangeRateSnapshotData(String base, LocalDate upstreamDate, Map<String, Double> rates) {
}
