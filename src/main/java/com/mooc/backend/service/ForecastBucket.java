package com.mooc.backend.service;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 5 day / 3 hour 预报的单个时间桶（OpenWeatherMap forecast 的解析产物，
 * change: add-travel-services，task 4.2）。
 *
 * <p><b>保留原始桶，不做按日聚合。</b>聚合（丢过去桶 → 按 {@code Asia/Shanghai} 分组 → 丢今天
 * → 丢桶数 &lt; 4 的尾日）是前端 {@code lib/travel/forecast.ts} 的职责（tasks 8.5 / 8.6）：
 * 「过去」相对请求时刻才成立，服务端缓存里固化聚合结果会把缓存时间误当请求时间。
 *
 * <p>snake_case 用 {@code @JsonProperty} 逐字段标注（全仓无全局命名策略；本 record 进 Redis
 * 缓存 JSON，必须人眼可读——见 {@code WeatherCacheJson}）。
 *
 * @param dt          UTC unix 时间戳，<b>原样保真不本地化</b>——分组必须走它而非 {@code dt_txt}
 *                    （后者是 UTC 字符串，拿它的日期当当地日期用会整体漂移 8 小时，design §11.1）
 * @param tempMinC    桶内最低温（摄氏度，{@code units=metric} 前提同 {@link CurrentWeather}）
 * @param tempMaxC    桶内最高温
 * @param condition   {@code weather[].main} 的 condition group（Clear / Clouds / Rain / ...），
 *                    前端映射本地图标的键
 * @param description 上游描述文本
 * @param icon        OWM icon 代码，同 {@link CurrentWeather#icon()}：只作映射键，不作 URL
 */
public record ForecastBucket(long dt,
                             @JsonProperty("temp_min_c") double tempMinC,
                             @JsonProperty("temp_max_c") double tempMaxC,
                             String condition,
                             String description,
                             String icon) {
}
