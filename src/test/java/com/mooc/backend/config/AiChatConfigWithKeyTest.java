package com.mooc.backend.config;

import com.mooc.backend.BackendApplication;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

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
    ObjectProvider<ToolCallbackProvider> toolProviders;

    @Test
    void chatClientBeanIsPresentWhenApiKeySet() {
        assertThat(chatClients.getIfAvailable()).isNotNull();
    }

    @Test
    void travelToolCallbacksAreAvailableForTheChatClient() {
        // R6：工具定义一次、同 JVM 直调。工具提供者必须存在且至少带 weather/rates 两个 @Tool。
        // change: ai-spot-tools D1 —— 改用 orderedStream()：多提供者共存时 getIfAvailable()
        // 会因多候选抛 NoUniqueBeanDefinitionException（spike 实证，见 design.md D1）。
        List<ToolCallbackProvider> providers = toolProviders.orderedStream().toList();
        assertThat(providers).isNotEmpty();
        long tools = providers.stream()
                .mapToLong(p -> p.getToolCallbacks().length)
                .sum();
        assertThat(tools).isGreaterThanOrEqualTo(2);
    }
}
