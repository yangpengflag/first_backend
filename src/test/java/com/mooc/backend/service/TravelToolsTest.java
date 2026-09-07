package com.mooc.backend.service;
import com.mooc.backend.service.RatesToolResult;
import com.mooc.backend.service.TravelTools;
import com.mooc.backend.service.WeatherToolResult;

import com.mooc.backend.service.CurrentWeather;
import com.mooc.backend.service.ForecastBucket;
import com.mooc.backend.service.ExchangeRateService;
import com.mooc.backend.service.ExchangeRateView;
import com.mooc.backend.service.WeatherService;
import com.mooc.backend.service.WeatherView;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 工具单元测试（change: add-travel-services，task 6.1）。
 *
 * <p>工具是 {@code ai-chat-core} 将直接引用的对外契约，四条硬约定各有对应测试：
 *
 * <ul>
 *   <li><b>名字与签名固定</b>——{@code get_city_weather(citySlug)} / {@code get_exchange_rates(target?)}，
 *       snake_case（MCP 生态惯例）；届时更名需两个 change 同步改，注解值本身要被测试钉住；</li>
 *   <li><b>参数是策展 slug</b>——未收录的值得到明确的「未收录」结果且不触碰 Service
 *       （无界输入正是参考实现配额被打爆的根源，模型不该能拿任意字符串去查）；</li>
 *   <li><b>降级返回可读空态</b>——工具抛异常会变成模型看不懂的 stack trace，空态它能自然转述；</li>
 *   <li><b>stale 进返回值</b>——否则模型会把三天前的汇率当今天的说。</li>
 * </ul>
 */
class TravelToolsTest {

    private static final Instant FETCHED = Instant.parse("2026-09-06T09:00:00Z");

    private ExchangeRateService exchangeRateService;
    private WeatherService weatherService;
    private TravelTools tools;

    @BeforeEach
    void setUp() {
        exchangeRateService = mock(ExchangeRateService.class);
        weatherService = mock(WeatherService.class);
        tools = new TravelTools(exchangeRateService, weatherService, props());
    }

    /** base 币种取自配置（降级空态里也要带）。 */
    private static com.mooc.backend.config.TravelProperties props() {
        return new com.mooc.backend.config.TravelProperties(
                new com.mooc.backend.config.TravelProperties.ExchangeRate(true, "CNY",
                        "0 30 3 * * *", java.time.Duration.ofHours(26), java.time.Duration.ofDays(7)),
                new com.mooc.backend.config.TravelProperties.Weather(true, "test-key",
                        "0 0 */3 * * *", "en", java.time.Duration.ofHours(4), java.time.Duration.ofHours(24)),
                new com.mooc.backend.config.TravelProperties.Client(
                        java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5)),
                java.time.Duration.ofMinutes(5));
    }

    /** 工具名与签名固定（design §7）：ai-chat-core 的 prompt 会直接引用这两个名字。 */
    @Test
    void toolNamesAndSignaturesArePinned() throws NoSuchMethodException {
        Tool weather = TravelTools.class.getMethod("getCityWeather", String.class).getAnnotation(Tool.class);
        Tool rates = TravelTools.class.getMethod("getExchangeRates", String.class).getAnnotation(Tool.class);

        assertThat(weather).isNotNull();
        assertThat(weather.name()).isEqualTo("get_city_weather");
        assertThat(rates).isNotNull();
        assertThat(rates.name()).isEqualTo("get_exchange_rates");
    }

    /** 正常：Service 数据原样透出，message 是模型可直接转述的可读摘要。 */
    @Test
    void weatherToolReturnsServiceData() {
        when(weatherService.getWeather("hangzhou")).thenReturn(new WeatherView(FETCHED, false,
                new CurrentWeather(18.4, "overcast clouds", "04d", 72, 3.1),
                List.of(new ForecastBucket(1757046000L, 16.1, 20.2, "Clouds",
                        "overcast clouds", "04d"))));

        WeatherToolResult result = tools.getCityWeather("hangzhou");

        assertThat(result.stale()).isFalse();
        assertThat(result.fetchedAt()).isEqualTo(FETCHED);
        assertThat(result.current().tempC()).isEqualTo(18.4);
        assertThat(result.forecast()).hasSize(1);
        assertThat(result.message()).contains("Hangzhou").contains("overcast clouds");
    }

    /** 未收录 slug：明确的「未收录」结果，且完全不触碰 Service（更不该有上游调用）。 */
    @Test
    void weatherToolRejectsUnmappedSlugWithoutTouchingService() {
        WeatherToolResult result = tools.getCityWeather("atlantis");

        assertThat(result.current()).isNull();
        assertThat(result.message()).contains("atlantis");
        assertThat(result.message()).containsSubsequence("not", "curated");
        verifyNoInteractions(weatherService);
    }

    /** 降级（缓存空 / 停用 / 超 hard TTL）：可读空态而非抛异常，数据字段为 null。 */
    @Test
    void weatherToolReturnsReadableEmptyStateWhenUnavailable() {
        when(weatherService.getWeather("hangzhou")).thenReturn(null);

        assertThatCode(() -> {
            WeatherToolResult result = tools.getCityWeather("hangzhou");

            assertThat(result.current()).isNull();
            assertThat(result.forecast()).isNull();
            assertThat(result.stale()).isFalse();
            assertThat(result.message()).contains("temporarily unavailable").contains("hangzhou");
        }).doesNotThrowAnyException();
    }

    /** stale 进返回值：模型必须知道这是旧值。 */
    @Test
    void weatherToolSurfacesStaleFlag() {
        when(weatherService.getWeather("hangzhou")).thenReturn(new WeatherView(FETCHED, true,
                new CurrentWeather(18.4, "overcast clouds", "04d", 72, 3.1), List.of()));

        assertThat(tools.getCityWeather("hangzhou").stale()).isTrue();
    }

    /** 正常：整表透出 + 可读摘要（as_of 进 message，模型才能说明这是哪一天的牌价）。 */
    @Test
    void ratesToolReturnsWholeTable() {
        when(exchangeRateService.getRates()).thenReturn(new ExchangeRateView("CNY",
                LocalDate.parse("2026-09-04"), FETCHED, false,
                Map.of("USD", 0.14901, "JPY", 23.283)));

        RatesToolResult result = tools.getExchangeRates(null);

        assertThat(result.stale()).isFalse();
        assertThat(result.base()).isEqualTo("CNY");
        assertThat(result.rates()).containsEntry("USD", 0.14901).hasSize(2);
        assertThat(result.message()).contains("2026-09-04");
    }

    /** target 过滤：只回该币种（Omit for all，design §7 的签名约定）。 */
    @Test
    void ratesToolFiltersByTarget() {
        when(exchangeRateService.getRates()).thenReturn(new ExchangeRateView("CNY",
                LocalDate.parse("2026-09-04"), FETCHED, false,
                Map.of("USD", 0.14901, "JPY", 23.283)));

        RatesToolResult result = tools.getExchangeRates("usd");

        assertThat(result.rates()).containsOnlyKeys("USD");
    }

    /** target 不在上游返回键集：可读的「没有该币种」，不是空 map 更不是异常。 */
    @Test
    void ratesToolReportsUnknownTargetReadably() {
        when(exchangeRateService.getRates()).thenReturn(new ExchangeRateView("CNY",
                LocalDate.parse("2026-09-04"), FETCHED, false,
                Map.of("USD", 0.14901)));

        RatesToolResult result = tools.getExchangeRates("RUB");

        assertThat(result.rates()).isNull();
        assertThat(result.message()).contains("RUB");
    }

    /** 降级：可读空态而非抛异常。 */
    @Test
    void ratesToolReturnsReadableEmptyStateWhenUnavailable() {
        when(exchangeRateService.getRates()).thenReturn(null);

        assertThatCode(() -> {
            RatesToolResult result = tools.getExchangeRates(null);

            assertThat(result.rates()).isNull();
            assertThat(result.stale()).isFalse();
            assertThat(result.message()).contains("temporarily unavailable");
        }).doesNotThrowAnyException();
    }

    /** stale 进返回值。 */
    @Test
    void ratesToolSurfacesStaleFlag() {
        when(exchangeRateService.getRates()).thenReturn(new ExchangeRateView("CNY",
                LocalDate.parse("2026-09-04"), FETCHED, true, Map.of("USD", 0.14901)));

        assertThat(tools.getExchangeRates(null).stale()).isTrue();
    }
}
