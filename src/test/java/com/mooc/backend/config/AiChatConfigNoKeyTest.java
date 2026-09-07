package com.mooc.backend.config;

import com.mooc.backend.BackendApplication;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配行为（tasks 1.4）：开关开启但 api-key 为空（哨兵）→ 不装配 ChatClient，
 * context 仍正常启动（fail-closed）。
 */
@SpringBootTest(classes = BackendApplication.class,
        properties = {"app.ai-chat.enabled=true", "spring.ai.openai.api-key="})
class AiChatConfigNoKeyTest {

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @org.springframework.beans.factory.annotation.Autowired
    ObjectProvider<ChatClient> chatClients;

    @Test
    void chatClientBeanIsAbsentWhenApiKeyEmpty() {
        assertThat(chatClients.getIfAvailable()).isNull();
    }
}
