package com.mooc.backend.service;
import com.mooc.backend.service.ExchangeRateClient;
import com.mooc.backend.service.ExchangeRateSnapshotData;

import com.mooc.backend.config.TravelProperties;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 汇率客户端单元测试（change: add-travel-services，task 3.1）。
 *
 * <p>用 {@code MockRestServiceServer.bindTo(RestClient.Builder)}（spike 0.4 已确认 spring-test 6.2
 * 支持该重载），传输层被替换，测试不出网。
 *
 * <p><b>客户端必须接 {@code RestClient} 而非 {@code RestClient.Builder}。</b> 本 change 实测：
 * 客户端曾在构造器里调 {@code builder.requestFactory(...)} 设超时，把 bindTo 装的 mock factory
 * 顶掉，九条测试**全部静默打到真实 Frankfurter**——断言 4 个币种却收到 29 个真实币种，
 * 两条 {@code server.verify()} 报「0 request(s) executed」。超时改由 {@code TravelConfig} 装配。
 *
 * <p>四条失败分支各有各的理由：**429**——Frankfurter 无配额但有防滥用限流，跳过本轮等下个 tick；
 * **5xx**——同上；**超时**——read timeout 到点必须失败返回而不是挂住调度线程；**畸形 JSON**——
 * 上游改结构或返回 HTML 错误页时，不能让异常穿透到调度线程。
 *
 * <p>失败一律是 {@code Optional.empty()} 而非抛异常：调用方是刷新任务，它对四种失败的处置
 * 完全相同（保留旧值、跳过本轮），区分它们只会让调用方写四个一样的 catch。
 */
class ExchangeRateClientTest {

    private static final String URL = "https://api.frankfurter.dev/v1/latest?base=CNY";

    /** 200 正常：`date` 原样保真（它是上游牌价日，不是我们拉取的日期），`rates` 整表拿回。 */
    @Test
    void parsesSuccessfulResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"CNY","date":"2026-09-04",
                         "rates":{"USD":0.14901,"EUR":0.12821,"JPY":23.283,"KRW":201.22}}
                        """, MediaType.APPLICATION_JSON));

        Optional<ExchangeRateSnapshotData> out = client.fetchLatest();

        assertThat(out).isPresent();
        assertThat(out.get().base()).isEqualTo("CNY");
        assertThat(out.get().upstreamDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(out.get().rates())
                .containsEntry("USD", 0.14901)
                .containsEntry("JPY", 23.283)
                .hasSize(4);
        server.verify();
    }

    /**
     * <b>不传 {@code symbols=}</b>（design §3.1）：整表才能支撑面板的「Show all」，30 币种约 1KB，
     * 筛选省不下什么。这条断言的是 URL 的精确形状——加了 symbols 会让 requestTo 不匹配。
     */
    @Test
    void requestsWholeTableWithoutSymbolsFilter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"amount":1.0,"base":"CNY","date":"2026-09-04","rates":{"USD":0.14901}}
                """, MediaType.APPLICATION_JSON));

        client.fetchLatest();

        server.verify(); // requestTo 是精确匹配：多出 &symbols=... 就不会匹配上
    }

    /** base 取自配置而非硬编码，改配置就能改出网参数。 */
    @Test
    void usesConfiguredBaseCurrency() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props("EUR"));

        server.expect(requestTo("https://api.frankfurter.dev/v1/latest?base=EUR"))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"EUR","date":"2026-09-04","rates":{"USD":1.16}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchLatest()).isPresent();
        server.verify();
    }

    /** 429：跳过本轮，不做退避重试——有旧值兜底，下个 tick 自然重试（task 0.2 的结论）。 */
    @Test
    void tooManyRequestsYieldsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client.fetchLatest()).isEmpty();
    }

    /** 5xx 同样只是跳过本轮。 */
    @Test
    void serverErrorYieldsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(client.fetchLatest()).isEmpty();
    }

    /**
     * 传输层异常（连接被拒 / 读超时都走这条 {@code ResourceAccessException} 路径）不得穿透到调用方。
     * 真的等一个 read timeout 会让这条测试跑 5s，用 IO 异常代表同一类失败。
     */
    @Test
    void transportFailureYieldsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL)).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });

        assertThat(client.fetchLatest()).isEmpty();
    }

    /** 畸形 JSON / HTML 错误页：当失败处理，不能让反序列化异常穿透。 */
    @Test
    void malformedBodyYieldsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL))
                .andRespond(withSuccess("<html>gateway error</html>", MediaType.TEXT_HTML));

        assertThat(client.fetchLatest()).isEmpty();
    }

    /**
     * 200 但 {@code rates} 为空或缺失：结构合法、内容无用。写空表进缓存会把「上游返回了什么」
     * 与「我们有一份空数据」混为一谈，前端拿到 `rates: {}` 会渲染一个空列表而不是降级空态。
     */
    @Test
    void emptyRatesYieldsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"amount":1.0,"base":"CNY","date":"2026-09-04","rates":{}}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchLatest()).isEmpty();
    }

    /** 200 但缺 `date`：`upstream_date` 是要展示给用户的牌价日，没有它这份数据不可用。 */
    @Test
    void missingUpstreamDateYieldsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExchangeRateClient client = new ExchangeRateClient(builder.build(), props());

        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"amount":1.0,"base":"CNY","rates":{"USD":0.14901}}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchLatest()).isEmpty();
    }

    private static TravelProperties props() {
        return props("CNY");
    }

    private static TravelProperties props(String base) {
        return new TravelProperties(
                new TravelProperties.ExchangeRate(true, base, "0 30 3 * * *",
                        Duration.ofHours(26), Duration.ofDays(7)),
                null,
                new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofMinutes(5));
    }
}
