package com.mooc.backend.travel.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;
import com.mooc.backend.travel.client.CurrentWeather;
import com.mooc.backend.travel.client.ForecastBucket;
import com.mooc.backend.travel.domain.WeatherCityQueries;
import com.mooc.backend.travel.service.WeatherView;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * `GET /api/travel/weather?city={slug}` 响应（change: add-travel-services，task 5.2）。
 *
 * <p><b>{@code city_name} / {@code city_name_zh} 恒非空</b>（spec「天气响应携带城市名」）：
 * 它们来自策展映射（{@link WeatherCityQueries}），与上游可用性无关——数据全空时这两个字段
 * 仍在，面板才有东西可显示。
 *
 * <p><b>{@code forecast} 是原始 3h 时间桶，不是按日聚合</b>：聚合（丢过去桶 → 按
 * {@code Asia/Shanghai} 分组 → 丢今天 → 丢桶数 &lt; 4 的尾日）在前端 {@code lib/travel/forecast.ts}
 * （tasks 8.5 / 8.6）——「过去」相对用户请求时刻才成立，服务端读缓存时聚合会把缓存时间误当
 * 请求时间。spec 端点 Requirement 中 {@code forecast[]} 的 {@code {date, min_c, max_c}}
 * 形状对应面板聚合后的<b>展示</b>形态。
 *
 * <p>三个可空字段（{@code fetched_at} / {@code current} / {@code forecast}）的可空表达由
 * {@link TravelOpenApiCustomizer} 在文档层完成（springdoc 2.8.8 在 3.1 下丢弃
 * {@code @Schema(nullable = true)}），理由同 {@link TravelRatesResponse}（前端 strict TS）。
 */
public class TravelWeatherResponse extends BaseResponse {

    @JsonProperty("city_slug")
    private final String citySlug;

    @JsonProperty("city_name")
    @Schema(description = "英文城市名（界面主显语言），来自策展映射，恒非空")
    private final String cityName;

    @JsonProperty("city_name_zh")
    @Schema(description = "中文城市名（副显），来自策展映射，恒非空")
    private final String cityNameZh;

    @JsonProperty("fetched_at")
    @Schema(description = "本系统成功拉取该数据的时刻。无数据时为 null")
    private final Instant fetchedAt;

    @JsonProperty("stale")
    @Schema(description = "数据仍可用但已超 soft TTL（4h）。无数据时恒为 false")
    private final boolean stale;

    @JsonProperty("current")
    @Schema(description = "当前天气。未配 key / 停用 / 冷启动 / 超 hard TTL 时为 null")
    private final CurrentWeather current;

    @JsonProperty("forecast")
    @Schema(description = "3h 粒度原始预报桶（UTC unix 时间戳 dt）。可空性同 current")
    private final List<ForecastBucket> forecast;

    private TravelWeatherResponse(String citySlug, String cityName, String cityNameZh,
                                  Instant fetchedAt, boolean stale,
                                  CurrentWeather current, List<ForecastBucket> forecast) {
        super();
        this.citySlug = citySlug;
        this.cityName = cityName;
        this.cityNameZh = cityNameZh;
        this.fetchedAt = fetchedAt;
        this.stale = stale;
        this.current = current;
        this.forecast = forecast;
    }

    /** 降级（view 为 null）仍带双语名。slug 已由 Controller 保证策展在册，orElseThrow 不可达。 */
    public static TravelWeatherResponse from(String citySlug, WeatherView view) {
        WeatherCityQueries.Names names = WeatherCityQueries.namesFor(citySlug).orElseThrow();
        if (view == null) {
            return new TravelWeatherResponse(citySlug, names.en(), names.zh(), null, false, null, null);
        }
        return new TravelWeatherResponse(citySlug, names.en(), names.zh(),
                view.fetchedAt(), view.stale(), view.current(), view.forecast());
    }
}
