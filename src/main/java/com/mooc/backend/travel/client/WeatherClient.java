package com.mooc.backend.travel.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.mooc.backend.travel.config.TravelProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * OpenWeatherMap 天气客户端（Current Weather + 5 day / 3 hour forecast，
 * change: add-travel-services，task 4.2）。<b>不用 One Call</b>——历来需单独订阅且计费边界不明。
 *
 * <p><b>key 为空即哨兵，整个 bean 不装配</b>（{@link WeatherConfiguredCondition}，同
 * {@code SmtpMailSender} 的条件装配套路）：读端点天气字段返 null，刷新任务跳过。装配与否由
 * {@code TravelProperties.Weather#configured()}（enabled 且 key 非空白）决定。
 *
 * <p><b>超时不在本类设置</b>，由 {@code TravelConfig#travelRestClient} 装配好后注入——理由同
 * {@link ExchangeRateClient}：在这里调 {@code requestFactory(...)} 会顶掉测试装的 mock factory。
 *
 * <p><b>失败分流</b>（见 {@link WeatherFetchResult}）：401 / 403 是凭据被拒，独立类型；其余
 * （429 / 5xx / 超时 / 畸形响应 / 结构不完整）一律 {@code TransientFailure}，调用方保留旧值等下个 tick。
 *
 * <p><b>日志卫生（硬约束）</b>：key 以 {@code appid=} query 随 URL 传递，而 RestClient 异常的
 * {@code getMessage()} <b>含完整请求 URL</b>——因此任何失败分支只记端点名 + 状态码（或异常类名），
 * 绝不记 URL、appid 参数名或 key 取值，也不让异常消息透传到日志。
 */
@Component
@Conditional(WeatherClient.WeatherConfiguredCondition.class)
public class WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(WeatherClient.class);

    private static final String BASE_URL = "https://api.openweathermap.org/data/2.5";

    private final RestClient restClient;
    private final String apiKey;
    private final String lang;

    WeatherClient(RestClient travelRestClient, TravelProperties props) {
        this.restClient = travelRestClient;
        this.apiKey = props.weather().apiKey();
        this.lang = props.weather().lang();
    }

    /**
     * 拉取一个城市的当前天气与预报（两个上游调用，串行）。
     *
     * @param cityQuery 上游查询参数串，来自 {@code WeatherCityQueries}（如 {@code "q=Hangzhou,CN"}）。
     *                  units / lang / appid 由本客户端补齐，调用方不给也不该给
     */
    public WeatherFetchResult fetch(String cityQuery) {
        String query = cityQuery + "&units=metric&lang=" + lang + "&appid=" + apiKey;
        try {
            // 必须是绝对 URL：RestClient 的 uri(String) 对相对路径不拼 host（会变成 /weather?...）
            CurrentResponse current = call(BASE_URL + "/weather?" + query, "current", CurrentResponse.class);
            ForecastResponse forecast = call(BASE_URL + "/forecast?" + query, "forecast", ForecastResponse.class);
            return toResult(current, forecast);
        } catch (CredentialRejectedSignal signal) {
            log.warn("[travel] weather fetch rejected by upstream (status {}): check OPENWEATHER_API_KEY",
                    signal.status);
            return new WeatherFetchResult.CredentialRejected(signal.status);
        } catch (RestClientException e) {
            // 不记 e.getMessage()：RestClient 异常消息含完整 URL（带 appid）
            log.warn("[travel] weather fetch failed (keep last known values): {}",
                    e.getClass().getSimpleName());
            return new WeatherFetchResult.TransientFailure();
        }
    }

    /** 单端点调用。401 / 403 从任一端点冒泡到 {@link #fetch} 统一分流，状态码随行。 */
    private <T> T call(String pathWithQuery, String endpoint, Class<T> type) {
        try {
            return restClient.get().uri(pathWithQuery).retrieve().body(type);
        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new CredentialRejectedSignal(status.value());
            }
            log.warn("[travel] weather {} fetch failed (keep last known values): HTTP {}",
                    endpoint, status.value());
            throw e;
        }
    }

    /**
     * 结构合法但内容无用的响应也当瞬时失败：写残缺数据进缓存，前端会渲染出 NaN 温度或
     * 「零天预报」，而不是走降级空态——与汇率空 rates / 缺 date 当失败是同一条理由。
     */
    private WeatherFetchResult toResult(CurrentResponse current, ForecastResponse forecast) {
        if (current == null || current.main() == null || current.main().temp() == null
                || current.weather() == null || current.weather().length == 0 || current.weather()[0] == null) {
            log.warn("[travel] weather current response missing data (keep last known values)");
            return new WeatherFetchResult.TransientFailure();
        }
        if (forecast == null || forecast.list() == null || forecast.list().isEmpty()) {
            log.warn("[travel] weather forecast response missing buckets (keep last known values)");
            return new WeatherFetchResult.TransientFailure();
        }
        List<ForecastBucket> buckets = new ArrayList<>(forecast.list().size());
        for (Bucket b : forecast.list()) {
            Optional<ForecastBucket> parsed = toBucket(b);
            if (parsed.isEmpty()) {
                log.warn("[travel] weather forecast response has malformed bucket (keep last known values)");
                return new WeatherFetchResult.TransientFailure();
            }
            buckets.add(parsed.get());
        }
        WeatherEntry w = current.weather()[0];
        return new WeatherFetchResult.Success(
                new CurrentWeather(current.main().temp(), w.description(), w.icon(),
                        current.main().humidity() == null ? 0 : current.main().humidity(),
                        current.wind() == null || current.wind().speed() == null ? 0.0 : current.wind().speed()),
                buckets);
    }

    private Optional<ForecastBucket> toBucket(Bucket b) {
        if (b == null || b.main() == null || b.main().tempMin() == null || b.main().tempMax() == null
                || b.weather() == null || b.weather().length == 0 || b.weather()[0] == null) {
            return Optional.empty();
        }
        WeatherEntry w = b.weather()[0];
        return Optional.of(new ForecastBucket(b.dt(), b.main().tempMin(), b.main().tempMax(),
                w.main(), w.description(), w.icon()));
    }

    /** 内部控制流信号：凭据被拒从任意端点直接冒泡，不走「记日志 + 瞬时失败」通道。 */
    private static final class CredentialRejectedSignal extends RuntimeException {
        final int status;

        CredentialRejectedSignal(int status) {
            this.status = status;
        }
    }

    /** 上游响应形状。忽略未知字段以容忍上游加字段；{@code dt_txt} 不解析（分组必须走 {@code dt}）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentResponse(WeatherEntry[] weather, Main main, Wind wind) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ForecastResponse(List<Bucket> list) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WeatherEntry(String main, String description, String icon) {
    }

    /**
     * temp_min / temp_max 仅 forecast 用；current 侧为 null，只参与判空。
     * 全仓无全局 snake_case 命名策略（响应 DTO 都是逐字段 {@code @JsonProperty}），上游是
     * snake_case，这里必须显式标注——否则 {@code temp_min} 静默绑不上（实测：humidity 同名能绑，
     * tempMin 全 null，触发「malformed bucket」）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Main(@JsonProperty("temp") Double temp,
                        @JsonProperty("temp_min") Double tempMin,
                        @JsonProperty("temp_max") Double tempMax,
                        @JsonProperty("humidity") Integer humidity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Wind(Double speed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Bucket(long dt, Main main, WeatherEntry[] weather) {
    }

    /** {@code app.travel.weather.enabled=true} 且 key 非空白时才装配本 bean。 */
    static final class WeatherConfiguredCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            TravelProperties props = context.getBeanFactory()
                    .getBeanProvider(TravelProperties.class)
                    .getIfAvailable();
            return props != null && props.weather() != null && props.weather().configured();
        }
    }
}
