package com.mooc.backend.ai.config;

import com.mooc.backend.BackendApplication;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配行为（tasks 1.4）：api-key 非空 + 开关开启 → 自建 ChatClient bean。
 * 只建 bean 不出网（surefire 默认 app.ai-chat.enabled=false 被此处 test property 覆盖为 true）。
 */
@SpringBootTest(classes = BackendApplication.class,
        properties = {"app.ai-chat.enabled=true", "spring.ai.openai.api-key=sk-test-dummy"})
class AiChatConfigWithKeyTest {

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @org.springframework.beans.factory.annotation.Autowired
    ObjectProvider<ChatClient> chatClients;

    @Test
    void chatClientBeanIsPresentWhenApiKeySet() {
        assertThat(chatClients.getIfAvailable()).isNotNull();
    }
}
