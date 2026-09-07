package com.mooc.backend.service;

import com.mooc.backend.config.TravelProperties;
import com.mooc.backend.service.WeatherCityQueries;
import com.mooc.backend.service.ExchangeRateService;
import com.mooc.backend.service.ExchangeRateView;
import com.mooc.backend.service.WeatherService;
import com.mooc.backend.service.WeatherView;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 旅行数据的 AI 工具暴露（change: add-travel-services，task 6.2）。
 *
 * <p><b>工具只需定义一次</b>（design §7）：MCP server starter 会自动把 {@code @Tool} bean 转成
 * MCP tool，而 {@code ChatClient} 在同 JVM 内直接消费同一批 bean、零传输。MCP over HTTP 传输
 * 默认关闭（{@code spring.ai.mcp.server.enabled=false}）——这些工具的 HTTP 暴露面只属于进程外
 * MCP 客户端场景，启用前必须单独评估鉴权。<b>严禁为跑通 MCP 而在 SecurityConfig 里
 * {@code permitAll()} 放行 MCP 路径</b>：那等于全互联网都能调工具并烧尽 OpenWeatherMap 配额；
 * {@code anyRequest().authenticated()} 对 MCP 是 fail-closed 的正确行为。
 *
 * <p><b>复用读端点背后的同一 Service（只读缓存），因此同样不出网</b>；出网仅在刷新任务里。
 * 降级一律返回可读空态而非抛异常（异常会变成模型无法转述的堆栈）；参数是策展 slug 而非自由
 * 文本城市名——无界输入是参考实现配额被打爆的根源，模型给未收录的值应得到明确的「未收录」
 * 结果，而不是让系统去猜。
 */
@Component
public class TravelTools {

    private final ExchangeRateService exchangeRateService;
    private final WeatherService weatherService;
    private final String baseCurrency;

    public TravelTools(ExchangeRateService exchangeRateService, WeatherService weatherService,
                       TravelProperties props) {
        this.exchangeRateService = exchangeRateService;
        this.weatherService = weatherService;
        this.baseCurrency = props.exchangeRate().base();
    }

    /**
     * 获取策展城市的当前天气与多日预报。
     *
     * @param citySlug 城市 slug（11 个策展城市之一），不是自由文本城市名
     */
    @Tool(name = "get_city_weather",
            description = "Get current weather and a multi-day forecast for a curated Chinese city.")
    public WeatherToolResult getCityWeather(
            @ToolParam(description = "City slug, e.g. hangzhou. One of the 11 curated cities.")
            String citySlug) {
        if (WeatherCityQueries.namesFor(citySlug).isEmpty()) {
            return WeatherToolResult.unknownCity(citySlug, List.copyOf(WeatherCityQueries.slugs()));
        }
        WeatherView view = weatherService.getWeather(citySlug);
        if (view == null) {
            return WeatherToolResult.unavailable(citySlug);
        }
        String cityName = WeatherCityQueries.namesFor(citySlug).orElseThrow().en();
        String message = "%s: %.1f°C, %s.".formatted(cityName, view.current().tempC(),
                view.current().description());
        return new WeatherToolResult(citySlug, message, view.fetchedAt(), view.stale(),
                view.current(), view.forecast());
    }

    /**
     * 获取 CNY 基准的参考汇率表。
     *
     * @param target 可选 ISO 4217 币种代码（如 USD）；省略则返回全表
     */
    @Tool(name = "get_exchange_rates",
            description = "Get reference exchange rates from CNY. Display only, not for settlement.")
    public RatesToolResult getExchangeRates(
            @ToolParam(required = false, description = "Optional ISO 4217 code, e.g. USD. Omit for all.")
            String target) {
        ExchangeRateView view = exchangeRateService.getRates();
        if (view == null) {
            return RatesToolResult.unavailable(baseCurrency);
        }
        String message = "Reference rates from %s as of %s (upstream date). Display only, not for settlement."
                .formatted(view.base(), view.asOf());
        if (target == null || target.isBlank()) {
            return new RatesToolResult(message, view.base(), view.asOf(), view.fetchedAt(),
                    view.stale(), view.rates());
        }
        String normalized = target.trim().toUpperCase(Locale.ROOT);
        Double rate = view.rates().get(normalized);
        if (rate == null) {
            return RatesToolResult.unknownTarget(normalized, view.base(), view.asOf(), view.stale());
        }
        return new RatesToolResult(message, view.base(), view.asOf(), view.fetchedAt(),
                view.stale(), Map.of(normalized, rate));
    }
}
