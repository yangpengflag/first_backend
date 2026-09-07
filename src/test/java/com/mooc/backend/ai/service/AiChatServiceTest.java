package com.mooc.backend.ai.service;

import com.mooc.backend.ai.exception.AiChatUnavailableException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 装配态契约（tasks 1.3）：configured()/未装配降级/绝不触碰容器对象。
 * 只依赖 mock，不起 Spring context、不出网。
 */
class AiChatServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatClient> emptyProvider() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void configuredIsFalseWhenNoChatClientBean() {
        AiChatService service = new AiChatService(emptyProvider());

        assertThat(service.configured()).isFalse();
    }

    @Test
    void streamYieldsDegradationErrorWhenNotConfigured() {
        AiChatService service = new AiChatService(emptyProvider());

        assertThatThrownBy(() -> service.stream("session-1", "hello").blockFirst())
                .isInstanceOf(AiChatUnavailableException.class);
    }

    @Test
    void notConfiguredNeverAsksContainerForTheClient() {
        ObjectProvider<ChatClient> provider = emptyProvider();
        AiChatService service = new AiChatService(provider);

        service.stream("session-1", "hello").subscribe();

        verify(provider, never()).getObject();
    }

    @Test
    void configuredIsTrueWhenChatClientBeanPresent() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(ChatClient.class));
        AiChatService service = new AiChatService(provider);

        assertThat(service.configured()).isTrue();
    }
}
