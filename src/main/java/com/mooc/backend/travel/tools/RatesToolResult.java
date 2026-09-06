package com.mooc.backend.travel.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@code get_exchange_rates} 工具的返回值（change: add-travel-services，task 6.2）。
 *
 * <p>与 {@link WeatherToolResult} 同一条约定：{@code message} 恒非空（降级即空态文案，
 * 工具从不抛异常）；{@code stale} 进返回值，模型不得把数天前的牌价当当日数据陈述。
 * {@code message} 内含 {@code as_of} 牌价日期——只给数字不给日期是种微妙的不诚实
 * （spec「用途边界」）。
 */
public record RatesToolResult(String message, String base, LocalDate asOf, Instant fetchedAt,
                              boolean stale, Map<String, Double> rates) {

    /** 降级空态（停用 / 冷启动 / Redis 与快照皆无 / 超 hard TTL）。 */
    public static RatesToolResult unavailable(String base) {
        return new RatesToolResult(
                "Exchange rate data is temporarily unavailable. Please try again later.",
                base, null, null, false, null);
    }

    /** target 不在返回键集：可读的「没有该币种」。 */
    public static RatesToolResult unknownTarget(String target, String base, LocalDate asOf, boolean stale) {
        return new RatesToolResult(
                "No reference rate available for " + target + " against " + base + ".",
                base, asOf, null, stale, null);
    }
}
