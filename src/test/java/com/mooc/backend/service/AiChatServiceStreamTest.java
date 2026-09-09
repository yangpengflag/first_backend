package com.mooc.backend.service;
import com.mooc.backend.service.AiChatService;
import com.mooc.backend.service.AiPrompts;
import com.mooc.backend.service.ChatSessionService;
import com.mooc.backend.service.rag.KnowledgeRetriever;

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
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
    private KnowledgeRetriever retriever;
    private ChatClient client;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private ChatSession session;
    private ArgumentCaptor<List<Message>> messagesCaptor;
    @SuppressWarnings("unchecked")
    private ObjectProvider<ToolCallbackProvider> toolProviders = mock(ObjectProvider.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionService = mock(ChatSessionService.class);
        retriever = mock(KnowledgeRetriever.class);
        when(retriever.search(anyString())).thenReturn(List.of());
        client = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        messagesCaptor = ArgumentCaptor.forClass(List.class);

        session = ChatSession.create(SESSION_ID, null, Instant.now());

        when(toolProviders.orderedStream()).thenReturn(Stream.empty());

        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallbackProvider[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);

        when(sessionService.contextWindow()).thenReturn(20);
    }

    private AiChatService service() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new AiChatService(provider, toolProviders, sessionService, retriever, FIXED);
    }

    /** 把工具提供者换成给定集合，用于断言「每轮挂载」（change: ai-spot-tools D1）。 */
    private void withToolProviders(ToolCallbackProvider... providers) {
        when(toolProviders.orderedStream()).thenReturn(Stream.of(providers));
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
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        String system = systemCaptor.getValue();
        // 无检索命中：system = persona + 引用规则，逐字以 persona 开头、不含 knowledge section
        assertThat(system).startsWith(AiPrompts.PERSONA_PROMPT);
        assertThat(system).contains(AiPrompts.CITATION_RULES);
        assertThat(system).doesNotContain("# WanderChina site knowledge");
    }

    @Test
    void retrievedKnowledgeIsInjectedAsSeparateSectionWithoutRewritingPersona() {
        persistUserAndHistory("hi", "ok", "What to see near the base?");
        when(streamSpec.content()).thenReturn(Flux.just("Pandas"));
        Document hit = new Document("spot:chengdu-giant-panda-base:0",
                "Giant Panda Base opens 07:30 and is home to red pandas.",
                Map.of("type", "spot", "nameEn", "Giant Panda Base",
                        "url", "/spots/chengdu-giant-panda-base"));
        when(retriever.search("What to see near the base?")).thenReturn(List.of(hit));

        AiChatService service = service();
        service.stream(SESSION_ID, "What to see near the base?").blockLast();

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        String system = systemCaptor.getValue();
        assertThat(system).startsWith(AiPrompts.PERSONA_PROMPT);
        assertThat(system).contains("# WanderChina site knowledge");
        assertThat(system).contains("1. Giant Panda Base (/spots/chengdu-giant-panda-base)");
        assertThat(system).contains("opens 07:30");
        assertThat(system).contains("Do not fabricate sources");
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

    @Test
    void allToolProvidersAreMountedOnEveryRequest() {
        persistUserAndHistory("hi", "hello", "any hidden gems?");
        when(streamSpec.content()).thenReturn(Flux.just("yes"));
        ToolCallbackProvider travel = mock(ToolCallbackProvider.class);
        ToolCallbackProvider spots = mock(ToolCallbackProvider.class);
        withToolProviders(travel, spots);

        service().stream(SESSION_ID, "any hidden gems?").blockLast();

        // D1：多个工具提供者必须全部进入本轮请求（此前挂 client 级默认会导致 context 起不来）
        ArgumentCaptor<ToolCallbackProvider[]> captor =
                ArgumentCaptor.forClass(ToolCallbackProvider[].class);
        verify(requestSpec).toolCallbacks(captor.capture());
        assertThat(captor.getValue()).containsExactly(travel, spots);
    }

    @Test
    void toolCallbacksAreSkippedWhenNoProviderRegistered() {
        persistUserAndHistory("hi", "hello", "hello again");
        when(streamSpec.content()).thenReturn(Flux.just("hi"));

        service().stream(SESSION_ID, "hello again").blockLast();

        verify(requestSpec, never()).toolCallbacks(any(ToolCallbackProvider[].class));
    }
}
