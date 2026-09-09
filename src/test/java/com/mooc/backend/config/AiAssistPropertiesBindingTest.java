package com.mooc.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定测试（tasks 1.1 / design.md D6）：验证 {@code app.ai-assist.*} 经
 * {@code @ConfigurationProperties} 正确绑定到 {@link AiAssistProperties}（含嵌套 rate-limit / timeout）。
 * 用 {@link ApplicationContextRunner} 做轻量绑定验证，不加载全量上下文。
 */
class AiAssistPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsAllNestedProperties() {
        runner.withPropertyValues(
                        "app.ai-assist.max-input-chars=12000",
                        "app.ai-assist.polish-max-chars=8000",
                        "app.ai-assist.title-max-chars=200",
                        "app.ai-assist.rate-limit.per-ip-per-minute=20",
                        "app.ai-assist.rate-limit.per-user-per-minute=10",
                        "app.ai-assist.timeout.connect-ms=5000",
                        "app.ai-assist.timeout.read-ms=30000")
                .run(context -> {
                    AiAssistProperties props = context.getBean(AiAssistProperties.class);
                    assertThat(props.maxInputChars()).isEqualTo(12_000);
                    assertThat(props.polishMaxChars()).isEqualTo(8_000);
                    assertThat(props.titleMaxChars()).isEqualTo(200);
                    assertThat(props.rateLimit().perIpPerMinute()).isEqualTo(20);
                    assertThat(props.rateLimit().perUserPerMinute()).isEqualTo(10);
                    assertThat(props.timeout().connectMs()).isEqualTo(5_000);
                    assertThat(props.timeout().readMs()).isEqualTo(30_000);
                });
    }

    @Configuration
    @EnableConfigurationProperties(AiAssistProperties.class)
    static class TestConfig {
    }
}
