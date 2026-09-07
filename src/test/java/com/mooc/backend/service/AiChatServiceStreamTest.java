package com.mooc.backend.service;
import com.mooc.backend.service.AiChatService;
import com.mooc.backend.service.AiPrompts;
import com.mooc.backend.service.ChatSessionService;

import com.mooc.backend.entity.ChatMessage;
import com.mooc.backend.entity.ChatMessageRole;
import com.mooc.backend.entity.ChatSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式管线（tasks 3.1）：user 先落库、历史进 prompt、assistant 完成后落库、异常不落。
 * ChatClient 用 mock fluent 链，验证发给模型的 messages 内容与落库时机。
 */
class AiChatServiceStreamTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";

    private ChatSessionService sessionService;
    private ChatClient client;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private ChatSession session;
    private ArgumentCaptor<List<Message>> messagesCaptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionService = mock(ChatSessionService.class);
        client = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        messagesCaptor = ArgumentCaptor.forClass(List.class);

        session = ChatSession.create(SESSION_ID, null, Instant.now());

        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);

        when(sessionService.contextWindow()).thenReturn(20);
    }

    private AiChatService service() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new AiChatService(provider, sessionService, FIXED);
    }

    private static ChatMessage message(ChatMessageRole role, String content) {
        ChatSession any = ChatSession.create("session-x", null, Instant.now());
        return ChatMessage.create(any, role, content, Instant.now());
    }

    /** 真实语义：user 先落库，loadRecentMessages 会含刚落库的本轮消息。 */
    private void persistUserAndHistory(String roundOneUser, String roundOneAssistant,
                                       String currentQuestion) {
        when(sessionService.ensureSession(eq(SESSION_ID), any())).thenReturn(session);
        when(sessionService.loadRecentMessages(session, 20)).thenReturn(List.of(
                message(ChatMessageRole.USER, roundOneUser),
                message(ChatMessageRole.ASSISTANT, roundOneAssistant),
                message(ChatMessageRole.USER, currentQuestion)));
    }

    @Test
    void followUpPromptCarriesRoundOneHistoryAndCurrentQuestion() {
        persistUserAndHistory("hi", "I can help with Chengdu.", "What about pandas there?");
        when(streamSpec.content()).thenReturn(Flux.just("Sure", ", pandas!"));
        AiChatService service = service();

        String answer = service.stream(SESSION_ID, "What about pandas there?")
                .reduce("", String::concat).block();

        verify(requestSpec).messages(messagesCaptor.capture());
        List<Message> sent = messagesCaptor.getValue();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).isInstanceOf(UserMessage.class);
        assertThat(sent.get(0).getText()).isEqualTo("hi");
        assertThat(sent.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(sent.get(1).getText()).isEqualTo("I can help with Chengdu.");
        assertThat(sent.get(2)).isInstanceOf(UserMessage.class);
        assertThat(sent.get(2).getText()).isEqualTo("What about pandas there?");
        assertThat(answer).isEqualTo("Sure, pandas!");
        verify(requestSpec).system(AiPrompts.SYSTEM_PROMPT);
    }

    @Test
    void assistantAnswerIsPersistedAfterFullCompletion() {
        persistUserAndHistory("hi", "I can help with Chengdu.", "plan 1 day");
        when(streamSpec.content()).thenReturn(Flux.just("Day 1: ", "Chengdu", " pandas."));
        AiChatService service = service();

        service.stream(SESSION_ID, "plan 1 day").blockLast();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionService).appendAssistantMessage(eq(session), contentCaptor.capture(), any());
        assertThat(contentCaptor.getValue()).isEqualTo("Day 1: Chengdu pandas.");
    }

    @Test
    void userMessagePersistedBeforeStreamStarts() {
        persistUserAndHistory("hi", "hello", "new question");
        when(streamSpec.content()).thenReturn(Flux.empty());
        AiChatService service = service();

        service.stream(SESSION_ID, "new question").blockLast();

        var inOrder = inOrder(sessionService);
        inOrder.verify(sessionService).ensureSession(eq(SESSION_ID), any());
        inOrder.verify(sessionService).appendUserMessage(eq(session), eq("new question"), any());
        inOrder.verify(sessionService).loadRecentMessages(session, 20);
        inOrder.verify(sessionService).appendAssistantMessage(eq(session), anyString(), any());
    }

    @Test
    void streamErrorSkipsAssistantPersistenceButKeepsUserMessage() {
        persistUserAndHistory("hi", "hello", "will fail");
        when(streamSpec.content()).thenReturn(Flux.error(new RuntimeException("upstream boom")));
        AiChatService service = service();

        try {
            service.stream(SESSION_ID, "will fail").blockLast();
        } catch (RuntimeException expected) {
            assertThat(expected).hasMessage("upstream boom");
        }

        verify(sessionService).appendUserMessage(eq(session), eq("will fail"), any());
        verify(sessionService, never()).appendAssistantMessage(any(), any(), any());
    }
}
