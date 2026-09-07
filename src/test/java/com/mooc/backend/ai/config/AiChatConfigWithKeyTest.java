package com.mooc.backend.ai.config;

import com.mooc.backend.BackendApplication;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配行为（tasks 1.4）：api-key 非空 + 开关开启 → 自建 ChatClient bean，
 * 且 travel 的 {@code MethodToolCallbackProvider} 工具回调在容器内可消费
 * （review P1：R6 工具接线断言）。
 * 只建 bean 不出网（surefire 默认 app.ai-chat.enabled=false 被此处 test property 覆盖为 true）。
 */
@SpringBootTest(classes = BackendApplication.class,
        properties = {"app.ai-chat.enabled=true", "spring.ai.openai.api-key=sk-test-dummy"})
class AiChatConfigWithKeyTest {

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @org.springframework.beans.factory.annotation.Autowired
    ObjectProvider<ChatClient> chatClients;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @org.springframework.beans.factory.annotation.Autowired
    ObjectProvider<MethodToolCallbackProvider> toolProviders;

    @Test
    void chatClientBeanIsPresentWhenApiKeySet() {
        assertThat(chatClients.getIfAvailable()).isNotNull();
    }

    @Test
    void travelToolCallbacksAreAvailableForTheChatClient() {
        // R6：工具定义一次、同 JVM 直调。provider bean 必须存在且带 weather/rates 两个 @Tool
        MethodToolCallbackProvider provider = toolProviders.getIfAvailable();
        assertThat(provider).isNotNull();
        assertThat(provider.getToolCallbacks()).hasSizeGreaterThanOrEqualTo(2);
    }
}
