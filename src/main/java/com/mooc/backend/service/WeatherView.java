package com.mooc.backend.service;

import com.mooc.backend.service.CurrentWeather;
import com.mooc.backend.service.ForecastBucket;

import java.time.Instant;
import java.util.List;

/**
 * 天气读结果（change: add-travel-services，task 4.4）。
 *
 * <p>与 {@link ExchangeRateView} 同层：Service 对 Controller / {@code @Tool} 的统一读出形状。
 * 不含 {@code city_name} / {@code city_name_zh}——它们来自策展映射、与上游可用性无关，
 * 由 Controller 层拼装（这两字段在缓存全空时也必须非空，spec「天气响应携带城市名」）。
 *
 * <p>{@code stale} 是读取时刻相对 {@code fetchedAt} 的派生值（照 {@code ExchangeRateCacheJson}
 * 的同一条理由，不进缓存、随读取现算）。
 */
public record WeatherView(Instant fetchedAt, boolean stale, CurrentWeather current,
                          List<ForecastBucket> forecast) {
}
