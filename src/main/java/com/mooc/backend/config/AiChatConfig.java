package com.mooc.backend.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;

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
@EnableConfigurationProperties({AiChatProperties.class, AiAssistProperties.class})
public class AiChatConfig {

    /**
     * 对话客户端（change: ai-spot-tools，D1 改造）。
     *
     * <p><b>工具不再挂在这里</b>：此前用 {@code defaultToolCallbacks(provider)} 把唯一的
     * {@code MethodToolCallbackProvider} 挂成 client 级默认，其代价是——一旦注册第二个 Provider，
     * {@code ObjectProvider#getIfAvailable()} 会因多候选抛 {@code NoUniqueBeanDefinitionException}
     * 让本 bean 创建失败、context fail-START（spike 实证见 design.md D1）。工具改由
     * {@code AiChatService} 在每轮请求上挂载（{@code toolCallbacks(ToolCallbackProvider...)}），
     * 于是：多 Provider 可共存、无 Provider 时对话照常、且写作辅助类请求天然不带工具。
     */
    @Bean
    @Conditional(AiChatEnabledCondition.class)
    ChatClient aiChatClient(Environment env, AiAssistProperties aiAssistProperties) {
        String baseUrl = env.getProperty("spring.ai.openai.base-url",
                "https://dashscope.aliyuncs.com/compatible-mode");
        String apiKey = env.getProperty("spring.ai.openai.api-key", "");
        String model = env.getProperty("spring.ai.openai.chat.options.model", "qwen-plus");

        // 同步调用超时（change: ai-post-assist，tasks 1.3 / design.md D5）：仅约束 assist 走的
        // 同步 RestClient（OpenAiApi.chatCompletionEntity），不影响 SSE 对话（流式走 WebClient，
        // 上界由 controller 既有的 UPSTREAM_TIMEOUT(90s) + EMITTER_TIMEOUT(120s) 承担）。
        // RestClient.Builder 无 connect/read 超时便捷方法，需经 requestFactory 注入带超时的
        // SimpleClientHttpRequestFactory。
        // 若未显式配置 app.ai-assist.timeout.*，回退到 design D5 默认值，避免 timeout() 为 null 时 NPE
        // （部分测试上下文不绑定 ai-assist 配置）。
        AiAssistProperties.Timeout timeout = aiAssistProperties.timeout();
        int connectMs = timeout != null ? timeout.connectMs() : 5000;
        int readMs = timeout != null ? timeout.readMs() : 30000;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readMs));
        RestClient.Builder restBuilder = RestClient.builder().requestFactory(requestFactory);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restBuilder)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .toolCallingManager(DefaultToolCallingManager.builder().build())
                .retryTemplate(new RetryTemplate())
                .observationRegistry(ObservationRegistry.create())
                .build();

        return ChatClient.builder(chatModel).build();
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
