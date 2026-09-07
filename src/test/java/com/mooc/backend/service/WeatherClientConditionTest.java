package com.mooc.backend.service;
import com.mooc.backend.service.WeatherClient;

import com.mooc.backend.config.TravelProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code WeatherClient} 装配条件的回归防线（曾因 {@code @Conditional} 在 bean 定义阶段经
 * {@code TravelProperties} bean 取值而恒 false、即使 key 正确也永不装配）。
 *
 * <p>本测试在最小上下文中验证条件的三态：key 有效 + enabled → 装配；key 空 → 不装配；
 * enabled=false → 不装配。若将来有人把条件改回「依赖 bean 生命周期」或弄丢空值哨兵，
 * 这里红灯。
 */
class WeatherClientConditionTest {

    private final ApplicationContextRunner base = new ApplicationContextRunner()
            .withUserConfiguration(ImportWeatherClient.class)
            .withBean(RestClient.class, () -> RestClient.create())
            .withBean(TravelProperties.class, () -> props());

    @Test
    void assemblesClientWhenEnabledWithNonBlankApiKey() {
        base.withPropertyValues(
                        "app.travel.weather.enabled=true",
                        "app.travel.weather.api-key=test-key-123")
                .run(ctx -> assertThat(ctx).hasSingleBean(WeatherClient.class));
    }

    @Test
    void doesNotAssembleClientWhenApiKeyBlank() {
        // 缺 api-key（空值哨兵）→ 不装配
        base.withPropertyValues("app.travel.weather.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WeatherClient.class));
    }

    @Test
    void doesNotAssembleClientWhenDisabled() {
        base.withPropertyValues(
                        "app.travel.weather.enabled=false",
                        "app.travel.weather.api-key=test-key-123")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WeatherClient.class));
    }

    @TestConfiguration
    @Import(WeatherClient.class)
    static class ImportWeatherClient {
    }

    private TravelProperties props() {
        return new TravelProperties(
                new TravelProperties.ExchangeRate(true, "CNY", "0 30 3 * * *",
                        Duration.ofHours(26), Duration.ofDays(7)),
                new TravelProperties.Weather(true, "dummy", "0 0 */3 * * *", "en",
                        Duration.ofHours(4), Duration.ofHours(24)),
                new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofMinutes(5));
    }
}
