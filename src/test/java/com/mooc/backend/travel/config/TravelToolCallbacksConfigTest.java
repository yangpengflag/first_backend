package com.mooc.backend.travel.config;

import com.mooc.backend.travel.service.ExchangeRateService;
import com.mooc.backend.travel.service.WeatherService;
import com.mooc.backend.travel.tools.TravelTools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code ai-chat-core} 接线的回归防线：确认 {@link TravelToolCallbacksConfig} 把名字固定的两个
 * {@code @Tool} 方法通过 {@link MethodToolCallbackProvider} 注册为工具（spec R11.1「定义一次、
 * 同 JVM 直调」）。若实现退化成「不注册 / 名字漂移 / 依赖自动发现 @Tool」，此测试红灯。
 */
class TravelToolCallbacksConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TravelToolCallbacksConfig.class)
            .withBean(TravelTools.class, () -> new TravelTools(
                    mock(ExchangeRateService.class),
                    mock(WeatherService.class),
                    props()));

    @Test
    void registersTheTwoPinnedToolsViaToolCallbackProvider() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MethodToolCallbackProvider.class);
            ToolCallbackProvider provider = ctx.getBean(MethodToolCallbackProvider.class);
            Set<String> toolNames = Stream.of(provider.getToolCallbacks())
                    .map(ToolCallback::getToolDefinition)
                    .map(td -> td.name())
                    .collect(Collectors.toSet());
            // spec R11.2：工具名固定为这两个，ai-chat-core 直接引用
            assertThat(toolNames).isEqualTo(Set.of("get_city_weather", "get_exchange_rates"));
        });
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
