package com.mooc.backend.service;

import com.mooc.backend.exception.AiChatUnavailableException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatClient 装配衔接测试（tasks 1.2 / design.md D3）：验证未装配抛降级、装配时 system/user 原样下传。
 * 桩 ChatClient 的 builder 链（不依赖真实模型调用，零出网）。
 */
class ChatClientTextGeneratorTest {

    @Test
    void throwsWhenChatClientNotConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        ChatClientTextGenerator generator = new ChatClientTextGenerator(empty);

        assertThatThrownBy(() -> generator.generate("SYS", "USR"))
                .isInstanceOf(AiChatUnavailableException.class);
    }

    @Test
    void passesSystemAndUserPromptsThroughToChatClient() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);

        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn("generated text");

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        ChatClientTextGenerator generator = new ChatClientTextGenerator(provider);

        String result = generator.generate("SYSTEM_PROMPT", "USER_PROMPT");

        assertThat(result).isEqualTo("generated text");
        verify(spec).system("SYSTEM_PROMPT");
        verify(spec).user("USER_PROMPT");
    }
}
