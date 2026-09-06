package com.mooc.backend.travel.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mooc.backend.travel.config.TravelProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 天气客户端单元测试（change: add-travel-services，task 4.1）。
 *
 * <p>与 {@link ExchangeRateClientTest} 同一套传输层替换（{@code MockRestServiceServer} 绑
 * {@code RestClient.Builder}，spike 0.4 已确认支持）。关键差异一条：汇率无 key，所有失败同质；
 * 天气的 401 / 403 是<b>凭据被拒</b>——不可自愈，必须有人改 {@code OPENWEATHER_API_KEY}——
 * 所以归入独立的结果类型（{@code CredentialRejected}），与 429 / 超时 / 5xx（等下个 tick 自然
 * 重试的 {@code TransientFailure}）分开，供 Service 层输出指名配置项的告警（task 4.3）。
 *
 * <p><b>请求形状用精确 URL 匹配锁死</b>（{@code requestTo} 全串比对）：多出或少掉
 * {@code units=metric} / {@code lang} / {@code appid} 都会让期望匹配不上。units 是重点——
 * OWM 默认开尔文，漏了它 {@code 291.15} 会被前端当成「291°C」渲染。
 *
 * <p><b>日志卫生</b>（spec「告警与异常不泄露凭据」）：{@code appid=} query 携带凭据，任何失败
 * 分支的日志不得出现完整 URL、{@code appid} 参数名或 key 取值——只允许端点名 + 状态码。
 */
class WeatherClientTest {

    private static final String CURRENT_URL =
            "https://api.openweathermap.org/data/2.5/weather?q=Hangzhou,CN&units=metric&lang=en&appid=test-key";
    private static final String FORECAST_URL =
            "https://api.openweathermap.org/data/2.5/forecast?q=Hangzhou,CN&units=metric&lang=en&appid=test-key";

    private static final String CURRENT_OK = """
            {"weather":[{"main":"Clouds","description":"overcast clouds","icon":"04d"}],
             "main":{"temp":18.4,"temp_min":16.1,"temp_max":20.2,"humidity":72},
             "wind":{"speed":3.1},"dt":1757046000,"name":"Hangzhou","cod":200}
            """;
    private static final String FORECAST_OK = """
            {"cod":"200","cnt":1,
             "list":[{"dt":1757046000,
                      "main":{"temp":18.4,"temp_min":16.1,"temp_max":20.2},
                      "weather":[{"main":"Clouds","description":"overcast clouds","icon":"04d"}],
                      "dt_txt":"2026-09-05 06:00:00"}],
             "city":{"name":"Hangzhou","timezone":28800}}
            """;

    private ListAppender<ILoggingEvent> logAppender;

    @AfterEach
    void detachLogAppender() {
        if (logAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(WeatherClient.class);
            logger.detachAppender(logAppender);
            logAppender.stop();
            logAppender = null;
        }
    }

    /** 200 正常：两响应解析为结构化数据；{@code dt} 是上游 UTC unix 时间戳，原样保真不本地化。 */
    @Test
    void parsesCurrentAndForecast() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"coord":{"lon":120.15,"lat":30.25},
                         "weather":[{"id":804,"main":"Clouds","description":"overcast clouds","icon":"04d"}],
                         "main":{"temp":18.4,"feels_like":17.9,"temp_min":16.1,"temp_max":20.2,
                                 "pressure":1015,"humidity":72},
                         "wind":{"speed":3.1,"deg":120},
                         "dt":1757046000,"timezone":28800,"name":"Hangzhou","cod":200}
                        """, MediaType.APPLICATION_JSON));
        fx.server().expect(requestTo(FORECAST_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"cod":"200","cnt":2,
                         "list":[
                           {"dt":1757046000,
                            "main":{"temp":18.4,"temp_min":16.1,"temp_max":20.2,"humidity":72},
                            "weather":[{"main":"Clouds","description":"overcast clouds","icon":"04d"}],
                            "dt_txt":"2026-09-05 06:00:00"},
                           {"dt":1757056800,
                            "main":{"temp":20.1,"temp_min":17.5,"temp_max":21.0,"humidity":68},
                            "weather":[{"main":"Rain","description":"light rain","icon":"10d"}],
                            "dt_txt":"2026-09-05 09:00:00"}
                         ],
                         "city":{"name":"Hangzhou","timezone":28800}}
                        """, MediaType.APPLICATION_JSON));

        WeatherFetchResult out = fx.client().fetch("q=Hangzhou,CN");

        assertThat(out).isInstanceOf(WeatherFetchResult.Success.class);
        WeatherFetchResult.Success success = (WeatherFetchResult.Success) out;
        assertThat(success.current().tempC()).isEqualTo(18.4);
        assertThat(success.current().description()).isEqualTo("overcast clouds");
        assertThat(success.current().icon()).isEqualTo("04d");
        assertThat(success.current().humidity()).isEqualTo(72);
        assertThat(success.current().windSpeed()).isEqualTo(3.1);

        List<ForecastBucket> buckets = success.forecast();
        assertThat(buckets).hasSize(2);
        // dt 原样保真：上游的 UTC unix 时间戳，不做任何时区换算——按 Asia/Shanghai 分组是
        // 前端聚合层（lib/travel/forecast.ts）的职责，客户端提前本地化会双重偏移。
        assertThat(buckets.get(0).dt()).isEqualTo(1757046000L);
        assertThat(buckets.get(0).tempMinC()).isEqualTo(16.1);
        assertThat(buckets.get(0).tempMaxC()).isEqualTo(20.2);
        assertThat(buckets.get(0).condition()).isEqualTo("Clouds");
        assertThat(buckets.get(0).description()).isEqualTo("overcast clouds");
        assertThat(buckets.get(0).icon()).isEqualTo("04d");
        assertThat(buckets.get(1).dt()).isEqualTo(1757056800L);
        assertThat(buckets.get(1).condition()).isEqualTo("Rain");
        fx.server().verify(); // requestTo 精确匹配：units=metric / lang / appid 三者缺一即失败
    }

    /** lang 取自配置而非硬编码——上游 description 的语言由 app.travel.weather.lang 决定。 */
    @Test
    void usesConfiguredLang() {
        String currentUrl = "https://api.openweathermap.org/data/2.5/weather"
                + "?q=Hangzhou,CN&units=metric&lang=zh&appid=test-key";
        String forecastUrl = "https://api.openweathermap.org/data/2.5/forecast"
                + "?q=Hangzhou,CN&units=metric&lang=zh&appid=test-key";
        Fixture fx = fixture("zh");
        fx.server().expect(requestTo(currentUrl)).andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));
        fx.server().expect(requestTo(forecastUrl)).andRespond(withSuccess(FORECAST_OK, MediaType.APPLICATION_JSON));

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isInstanceOf(WeatherFetchResult.Success.class);
        fx.server().verify();
    }

    /** 401：key 未激活 / 拼错 / 被吊销。独立类型，Service 层要发出指名 OPENWEATHER_API_KEY 的告警。 */
    @Test
    void unauthorizedYieldsCredentialRejected() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(fx.client().fetch("q=Hangzhou,CN"))
                .isEqualTo(new WeatherFetchResult.CredentialRejected(401));
    }

    /** 403 在 forecast 上同样算凭据被拒——current 成功不能掩盖凭据问题。 */
    @Test
    void forbiddenOnForecastYieldsCredentialRejected() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));
        fx.server().expect(requestTo(FORECAST_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(fx.client().fetch("q=Hangzhou,CN"))
                .isEqualTo(new WeatherFetchResult.CredentialRejected(403));
    }

    /** 429：可自愈（下个 tick 重试），归入 TransientFailure，与凭据问题分开。 */
    @Test
    void tooManyRequestsYieldsTransientFailure() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());
    }

    /** forecast 侧瞬时失败：整轮作废——缓存条目必须同时含 current 与 forecast，半份不写。 */
    @Test
    void transientFailureOnForecastDiscardsWholeRound() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));
        fx.server().expect(requestTo(FORECAST_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());
    }

    /** 5xx 同为瞬时失败。 */
    @Test
    void serverErrorYieldsTransientFailure() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(withServerError());

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());
    }

    /** 传输层异常（连接被拒 / 读超时）：不得穿透到调度线程，归入 TransientFailure。 */
    @Test
    void transportFailureYieldsTransientFailure() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());
    }

    /** HTML 错误页 / 畸形 JSON：反序列化失败按瞬时失败处理。 */
    @Test
    void malformedBodyYieldsTransientFailure() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL))
                .andRespond(withSuccess("<html>gateway error</html>", MediaType.TEXT_HTML));

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());
    }

    /**
     * 200 但 forecast 列表为空：结构合法、内容无用。写空列表进缓存会让前端渲染「零天预报」
     * 而不是走降级空态——与汇率空 rates 当失败是同一条理由。
     */
    @Test
    void emptyForecastListYieldsTransientFailure() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(withSuccess(CURRENT_OK, MediaType.APPLICATION_JSON));
        fx.server().expect(requestTo(FORECAST_URL))
                .andRespond(withSuccess("""
                        {"cod":"200","cnt":0,"list":[],"city":{"name":"Hangzhou"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());
    }

    /** 200 但 current 缺 main / weather：缓存一份残缺当前天气会让面板渲染出 NaN 温度。 */
    @Test
    void missingCurrentDataYieldsTransientFailure() {
        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL))
                .andRespond(withSuccess("""
                        {"cod":200,"dt":1757046000,"name":"Hangzhou"}
                        """, MediaType.APPLICATION_JSON));
        fx.server().expect(requestTo(FORECAST_URL)).andRespond(withSuccess(FORECAST_OK, MediaType.APPLICATION_JSON));

        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());
    }

    /**
     * 日志卫生（spec「告警与异常不泄露凭据」）：凭据以 {@code appid=} query 随 URL 传递，
     * 任何失败分支的日志不得出现 URL、appid 参数名或 key 取值。
     */
    @Test
    void failureLogsContainNoUrlOrApiKey() {
        attachLogAppender();

        Fixture fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());

        fx = fixture("en");
        fx.server().expect(requestTo(CURRENT_URL)).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });
        assertThat(fx.client().fetch("q=Hangzhou,CN")).isEqualTo(new WeatherFetchResult.TransientFailure());

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> {
                    assertThat(message).doesNotContain("api.openweathermap.org");
                    assertThat(message).doesNotContain("appid");
                    assertThat(message).doesNotContain("test-key");
                });
    }

    private record Fixture(MockRestServiceServer server, WeatherClient client) {
    }

    /** 每个用例独立 builder + mock server：期望序列互不串扰。 */
    private static Fixture fixture(String lang) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(server, new WeatherClient(builder.build(), props(lang)));
    }

    private void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(WeatherClient.class)).addAppender(logAppender);
    }

    private static TravelProperties props(String lang) {
        return new TravelProperties(
                new TravelProperties.ExchangeRate(true, "CNY", "0 30 3 * * *",
                        Duration.ofHours(26), Duration.ofDays(7)),
                new TravelProperties.Weather(true, "test-key", "0 0 */3 * * *", lang,
                        Duration.ofHours(4), Duration.ofHours(24)),
                new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofMinutes(5));
    }
}
