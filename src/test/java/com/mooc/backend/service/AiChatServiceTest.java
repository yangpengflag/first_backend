package com.mooc.backend.service;
import com.mooc.backend.service.AiChatService;
import com.mooc.backend.service.ChatSessionService;

import com.mooc.backend.exception.AiChatUnavailableException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 装配态契约（tasks 1.3）：configured()/未装配降级/绝不触碰容器对象。
 * 流式管线行为见 {@link AiChatServiceStreamTest}。
 */
class AiChatServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatClient> emptyProvider() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private AiChatService service(ObjectProvider<ChatClient> provider) {
        return new AiChatService(provider, mock(ChatSessionService.class), FIXED);
    }

    @Test
    void configuredIsFalseWhenNoChatClientBean() {
        assertThat(service(emptyProvider()).configured()).isFalse();
    }

    @Test
    void streamYieldsDegradationErrorWhenNotConfigured() {
        AiChatService s = service(emptyProvider());

        assertThatThrownBy(() -> s.stream("session-1", "hello").blockFirst())
                .isInstanceOf(AiChatUnavailableException.class);
    }

    @Test
    void notConfiguredNeverAsksContainerForTheClient() {
        ObjectProvider<ChatClient> provider = emptyProvider();
        AiChatService s = service(provider);

        s.stream("session-1", "hello").subscribe();

        verify(provider, never()).getObject();
    }

    @Test
    void configuredIsTrueWhenChatClientBeanPresent() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(ChatClient.class));
        AiChatService s = service(provider);

        assertThat(s.configured()).isTrue();
    }
}
