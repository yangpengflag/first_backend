package com.mooc.backend.service;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 当前天气（OpenWeatherMap Current Weather 的解析产物，change: add-travel-services，task 4.2）。
 *
 * <p>温度在客户端层已是摄氏度——前提是请求带了 {@code units=metric}（由
 * {@link WeatherClient} 的精确 URL 断言锁死）。字段集对齐 design §6 的 {@code current} 形状：
 * {@code temp_c} / {@code description} / {@code icon} / {@code humidity} / {@code wind_speed}。
 *
 * <p>snake_case 用 {@code @JsonProperty} 逐字段标注（全仓无全局命名策略；本 record 进 Redis
 * 缓存 JSON，必须人眼可读——见 {@code WeatherCacheJson}）。
 *
 * @param icon        OWM 的 icon 代码（如 {@code 04d}）。仅作前端映射 condition group 的键出网，
 *                    不作图片 URL 用——上游 CDN 会把访客 IP 与访问时间送给第三方（design §3.2）
 * @param description 上游描述文本，语言由 {@code app.travel.weather.lang} 决定
 * @param windSpeed   米/秒。上游极少缺省，缺省按 0 处理（display-only，无下游算术）
 */
public record CurrentWeather(@JsonProperty("temp_c") double tempC,
                             String description,
                             String icon,
                             int humidity,
                             @JsonProperty("wind_speed") double windSpeed) {
}
