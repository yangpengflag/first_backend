package com.mooc.backend.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 汇率读结果（change: add-travel-services，task 3.4）。
 *
 * <p>服务层与调用方（controller / `@Tool`）之间的载体，<b>不是 HTTP 响应 DTO</b>：
 * 不带 {@code @JsonProperty}、不继承 {@code BaseResponse}。出网形状由 {@code travel/api} 下的
 * 响应 DTO 负责——两个调用方（HTTP 与 AI 工具）对同一份数据的呈现要求不同，
 * 让服务层直接返回出网 DTO 会把 HTTP 的字段命名约定强加给工具返回值。
 *
 * <p><b>降级不由本类表达</b>：服务在停用 / 超 hard TTL / 无数据时返回 {@code null} 而非一个空的
 * {@code ExchangeRateView}。理由是「有数据但都是旧的」（{@code stale=true}）与「没有数据」
 * 是两种不同状态，用同一个对象的空字段表示会让调用方分不清。
 *
 * @param base      基准币种
 * @param asOf      上游牌价日期（{@code upstream_date}）。**仅展示**，不参与 TTL 判定
 * @param fetchedAt 我们拉到这份数据的时刻。**TTL 判定的唯一依据**
 * @param stale     {@code fetchedAt} 超 soft TTL。上到线是为了让 UI 与模型都能诚实标注
 * @param rates     汇率表，{@code 1 base = value target}；非空
 */
public record ExchangeRateView(String base, LocalDate asOf, Instant fetchedAt, boolean stale,
                               Map<String, Double> rates) {
}
