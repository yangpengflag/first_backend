package com.mooc.backend.service;

import com.mooc.backend.service.CurrentWeather;
import com.mooc.backend.service.ForecastBucket;

import java.time.Instant;
import java.util.List;

/**
 * {@code get_city_weather} 工具的返回值（change: add-travel-services，task 6.2）。
 *
 * <p><b>三个设计点</b>（design §7）：
 * <ul>
 *   <li>{@code message} 恒非空且人类可读——模型把它直接转述给用户；降级时它就是空态文案，
 *       工具因此<b>从不抛异常</b>（异常会变成模型无法转述的 stack trace）；</li>
 *   <li>{@code stale} 进返回值——否则模型会把数小时前的天气当当前实况说；</li>
 *   <li>数据字段（{@code current} / {@code forecast} / {@code fetchedAt}）降级时为 null，
 *       模型以 message 为准。</li>
 * </ul>
 */
public record WeatherToolResult(String citySlug, String message, Instant fetchedAt, boolean stale,
                                CurrentWeather current, List<ForecastBucket> forecast) {

    public WeatherToolResult {
        forecast = forecast == null ? null : List.copyOf(forecast);
    }

    /** 降级空态（缓存空 / 功能停用 / 超 hard TTL）。 */
    public static WeatherToolResult unavailable(String citySlug) {
        return new WeatherToolResult(citySlug,
                "Weather data is temporarily unavailable for " + citySlug + ". Please try again later.",
                null, false, null, null);
    }

    /** 未收录 slug：明确的「未收录」结果，列出可选值供模型自我纠正。 */
    public static WeatherToolResult unknownCity(String citySlug, List<String> curatedSlugs) {
        return new WeatherToolResult(citySlug,
                citySlug + " is not part of the curated cities. Available slugs: "
                        + String.join(", ", curatedSlugs) + ".",
                null, false, null, null);
    }
}
