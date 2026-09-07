package com.mooc.backend.service;

import java.util.List;

/**
 * 一次城市天气拉取的结果（change: add-travel-services，task 4.2）。
 *
 * <p>天气与汇率的关键差异：汇率无 key，所有失败同质（一个 {@code Optional.empty()} 够了）；
 * 天气的 {@code 401} / {@code 403} 是<b>凭据被拒</b>——不可自愈，等下个 tick 没有用，必须有人
 * 改 {@code OPENWEATHER_API_KEY}。故失败分两个类型，供 Service 层分流：
 *
 * <ul>
 *   <li>{@link CredentialRejected}——凭据问题，输出指名配置项的可行动告警（独立节流窗口）；</li>
 *   <li>{@link TransientFailure}——429 / 5xx / 超时 / 畸形响应，保留旧值、跳过本轮，下个 tick 自然重试。</li>
 * </ul>
 *
 * <p>成功时 {@code current} 与 {@code forecast} 必须同时在场：缓存条目是原子整体，
 * 半份（只有当前天气没有预报）宁可整轮作废。
 */
public sealed interface WeatherFetchResult {

    record Success(CurrentWeather current, List<ForecastBucket> forecast) implements WeatherFetchResult {
        public Success {
            forecast = List.copyOf(forecast);
        }
    }

    /** 凭据被拒，{@code status} 为上游状态码（401 / 403）。 */
    record CredentialRejected(int status) implements WeatherFetchResult {
    }

    /** 可自愈的瞬时失败：限流 / 5xx / 传输异常 / 响应结构不完整。 */
    record TransientFailure() implements WeatherFetchResult {
    }
}
