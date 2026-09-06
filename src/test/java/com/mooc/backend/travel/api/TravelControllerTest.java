package com.mooc.backend.travel.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mooc.backend.auth.ratelimit.RateLimitFilter;
import com.mooc.backend.auth.ratelimit.RateLimitProperties;
import com.mooc.backend.auth.ratelimit.RateLimiter;
import com.mooc.backend.auth.security.JwtAuthFilter;
import com.mooc.backend.auth.security.UserStatusFilter;
import com.mooc.backend.travel.client.CurrentWeather;
import com.mooc.backend.travel.client.ForecastBucket;
import com.mooc.backend.travel.config.TravelProperties;
import com.mooc.backend.travel.service.ExchangeRateService;
import com.mooc.backend.travel.service.ExchangeRateView;
import com.mooc.backend.travel.service.WeatherService;
import com.mooc.backend.travel.service.WeatherView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旅行读端点切片测试（change: add-travel-services，task 5.1 / 5.3）。
 *
 * <p>仓内首个 {@code @WebMvcTest}。选切片而非 {@code @SpringBootTest} 的理由（design §1.1）：
 * 请求路径上<b>没有 HTTP 客户端</b>，切片天然不出网，也天然不用装 Redis / 数据源——mock 两个
 * Service 即可覆盖全部响应分支。真实 {@code SecurityConfig}（{@code @Import}）参与测试，
 * 放行与兜底规则因此被真实验证：未认证 GET 必须直通（后面的用例全都不带凭证），
 * 而写方法必须仍被 {@code anyRequest().authenticated()} 挡住。
 *
 * <p>三个安全过滤器以<b>真实实例 + mock 依赖</b>入列（它们的行为属于各自模块的测试）。
 * 不能用 Mockito 直接 mock 过滤器类：Mockito 不拦截 {@code OncePerRequestFilter.doFilter}
 * 这个 final 方法，真实实现会调到被桩化的 {@code doFilterInternal}——后者什么都不做、
 * <b>也不继续过滤链</b>，于是每个请求都得到 200 + 空 body、到不了任何 handler（实测踩过）。
 * 而真实过滤器在无凭证请求下的行为就是「直接放行链」，依赖 mock 什么都不用桩。
 */
@WebMvcTest(TravelController.class)
@Import({com.mooc.backend.auth.config.SecurityConfig.class,
        TravelControllerTest.TravelPropsConfig.class, TravelControllerTest.FilterConfig.class})
class TravelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @MockitoBean
    private WeatherService weatherService;

    /** 真实 SecurityConfig 需要的三个过滤器 bean，依赖全部 mock（本切片的请求不触发它们）。 */
    @TestConfiguration
    static class FilterConfig {

        @Bean
        RateLimitFilter rateLimitFilter(ObjectMapper objectMapper) {
            return new RateLimitFilter(org.mockito.Mockito.mock(RateLimiter.class), objectMapper,
                    org.mockito.Mockito.mock(RateLimitProperties.class));
        }

        @Bean
        JwtAuthFilter jwtAuthFilter() {
            return new JwtAuthFilter(org.mockito.Mockito.mock(com.mooc.backend.auth.service.TokenService.class));
        }

        @Bean
        UserStatusFilter userStatusFilter(ObjectMapper objectMapper) {
            return new UserStatusFilter(org.mockito.Mockito.mock(com.mooc.backend.auth.domain.UserRepository.class),
                    objectMapper, java.time.Clock.systemUTC());
        }
    }

    /** 切片不含 @ConfigurationProperties 绑定；直接给一个 bean。 */
    @TestConfiguration
    static class TravelPropsConfig {
        @Bean
        TravelProperties travelProperties() {
            return new TravelProperties(
                    new TravelProperties.ExchangeRate(true, "CNY", "0 30 3 * * *",
                            Duration.ofHours(26), Duration.ofDays(7)),
                    new TravelProperties.Weather(true, "test-key", "0 0 */3 * * *", "en",
                            Duration.ofHours(4), Duration.ofHours(24)),
                    new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                    Duration.ofMinutes(5));
        }
    }

    /** 正常：未登录可读（本用例不带任何凭证），字段 snake_case，两个时间戳同现。 */
    @Test
    void ratesReturnsFullDataWithoutAuthentication() throws Exception {
        when(exchangeRateService.getRates()).thenReturn(new ExchangeRateView("CNY",
                LocalDate.parse("2026-09-04"), Instant.parse("2026-09-05T03:30:12Z"),
                false, Map.of("USD", 0.14901, "EUR", 0.12821)));

        mockMvc.perform(get("/api/travel/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("CNY"))
                .andExpect(jsonPath("$.as_of").value("2026-09-04"))
                .andExpect(jsonPath("$.fetched_at").value("2026-09-05T03:30:12Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.rates.USD").value(0.14901))
                .andExpect(jsonPath("$.rates.EUR").value(0.12821));
    }

    /** 降级：停用 / 冷启动 / 超 hard TTL → 200 + 五个可空字段为显式 null，不是 5xx 也不是空对象。 */
    @Test
    void ratesDegradedReturns200WithExplicitNullFields() throws Exception {
        when(exchangeRateService.getRates()).thenReturn(null);

        mockMvc.perform(get("/api/travel/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("CNY"))
                .andExpect(jsonPath("$.rates").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.as_of").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fetched_at").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.stale").value(false));
    }

    /** stale 上到线（design §2）：前端不加提示就得靠这个布尔。 */
    @Test
    void ratesSurfacesStaleFlag() throws Exception {
        when(exchangeRateService.getRates()).thenReturn(new ExchangeRateView("CNY",
                LocalDate.parse("2026-09-04"), Instant.parse("2026-09-05T03:30:12Z"),
                true, Map.of("USD", 0.14901)));

        mockMvc.perform(get("/api/travel/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true));
    }

    /** 天气正常：双语城市名必须与数据无关地存在（spec「天气响应携带城市名」），桶结构原样出网。 */
    @Test
    void weatherReturnsNamesCurrentAndRawBuckets() throws Exception {
        when(weatherService.getWeather("hangzhou")).thenReturn(new WeatherView(
                Instant.parse("2026-09-06T09:00:00Z"), false,
                new CurrentWeather(18.4, "overcast clouds", "04d", 72, 3.1),
                List.of(new ForecastBucket(1757046000L, 16.1, 20.2, "Clouds",
                        "overcast clouds", "04d"))));

        mockMvc.perform(get("/api/travel/weather").param("city", "hangzhou"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city_slug").value("hangzhou"))
                .andExpect(jsonPath("$.city_name").value("Hangzhou"))
                .andExpect(jsonPath("$.city_name_zh").value("杭州"))
                .andExpect(jsonPath("$.fetched_at").value("2026-09-06T09:00:00Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.current.temp_c").value(18.4))
                .andExpect(jsonPath("$.current.wind_speed").value(3.1))
                .andExpect(jsonPath("$.forecast[0].dt").value(1757046000))
                .andExpect(jsonPath("$.forecast[0].temp_min_c").value(16.1))
                .andExpect(jsonPath("$.forecast[0].condition").value("Clouds"));
    }

    /** 降级：数据为 null 但双语城市名仍在——它们来自策展映射，与上游可用性无关。 */
    @Test
    void weatherDegradedStillCarriesCityNames() throws Exception {
        when(weatherService.getWeather("hangzhou")).thenReturn(null);

        mockMvc.perform(get("/api/travel/weather").param("city", "hangzhou"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city_name").value("Hangzhou"))
                .andExpect(jsonPath("$.city_name_zh").value("杭州"))
                .andExpect(jsonPath("$.current").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.forecast").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 未收录 slug：404 + UPPER_SNAKE 错误码，且未登录也拿到 404 而非 401（公开端点先于鉴权生效）。 */
    @Test
    void unknownSlugReturns404WithUpperSnakeCode() throws Exception {
        mockMvc.perform(get("/api/travel/weather").param("city", "atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CITY_NOT_FOUND"));
    }

    /** 放行只覆盖 GET：写方法落回 {@code anyRequest().authenticated()} → 401。 */
    @Test
    void writeMethodsRemainAuthenticated() throws Exception {
        mockMvc.perform(post("/api/travel/rates").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * task 5.3 的第二半：放行清单不得含任何 MCP 路径——为跑通 MCP 而放行会让任意来源调用
     * 工具并烧尽外部配额。{@code /sse} 与 {@code /mcp/message} 是 MCP starter 的默认端点。
     */
    @Test
    void publicEndpointsListContainsNoMcpPaths() {
        org.assertj.core.api.Assertions.assertThat(com.mooc.backend.auth.config.SecurityConfig.PUBLIC_ENDPOINTS)
                .noneMatch(endpoint -> endpoint.contains("mcp") || endpoint.contains("/sse"))
                .noneMatch(endpoint -> endpoint.startsWith("/api/travel"));
    }
}
