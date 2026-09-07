package com.mooc.backend.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.retry.support.RetryTemplate;

import io.micrometer.observation.ObservationRegistry;

/**
 * AI 助手装配（change: ai-chat-core）。
 *
 * <p><b>为什么自建而非 auto-config</b>（spike 1.1 实测）：openai starter 的全部模型
 * auto-config（含 Chat）在空 api-key 下直接抛 {@code OpenAI API key must be set}，导致
 * context 启动失败（fail-START）。本仓要求空 key 也能启动（fail-closed），故 application.yml
 * 已把六类模型 auto-config 全置 {@code spring.ai.model.*=none}，这里在条件满足时自建模型。
 *
 * <p>装配条件 {@link AiChatEnabledCondition} 只读 {@code environment}（enabled 开关 + api-key
 * 非空），不依赖任何 bean——吸取 {@code WeatherConfiguredCondition} 曾在 bean 定义阶段读取
 * 未就绪 bean 而恒 false 的教训。条件不满足时本仓不产出 ChatClient bean，消费方经
 * {@code ObjectProvider} 感知未装配。
 */
@Configuration
@EnableConfigurationProperties(AiChatProperties.class)
public class AiChatConfig {

    @Bean
    @Conditional(AiChatEnabledCondition.class)
    ChatClient aiChatClient(Environment env,
                            ObjectProvider<MethodToolCallbackProvider> toolCallbackProviders) {
        String baseUrl = env.getProperty("spring.ai.openai.base-url", "https://api.deepseek.com");
        String apiKey = env.getProperty("spring.ai.openai.api-key", "");
        String model = env.getProperty("spring.ai.openai.chat.options.model", "deepseek-chat");

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .toolCallingManager(DefaultToolCallingManager.builder().build())
                .retryTemplate(new RetryTemplate())
                .observationRegistry(ObservationRegistry.create())
                .build();

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        // travel-services 已把天气/汇率两个 @Tool 以 MethodToolCallbackProvider 注册，
        // 同 JVM 零接线即获得工具（定义一次，travel spec 契约）。
        MethodToolCallbackProvider toolProvider = toolCallbackProviders.getIfAvailable();
        if (toolProvider != null) {
            builder.defaultToolCallbacks(toolProvider);
        }
        return builder.build();
    }

    /**
     * 装配条件：{@code app.ai-chat.enabled} 非 false（默认开）且
     * {@code spring.ai.openai.api-key} 非空。两者都只经 environment 读取。
     */
    public static class AiChatEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            // 用 Boot 宽松绑定读布尔（认 on/yes/true 等变体），避免手写字符串比较
            // 与 @ConfigurationProperties 的绑定语义分叉（review F2）。
            Boolean enabled = env.getProperty("app.ai-chat.enabled", Boolean.class, true);
            if (!Boolean.TRUE.equals(enabled)) {
                return false;
            }
            String apiKey = env.getProperty("spring.ai.openai.api-key", "");
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
