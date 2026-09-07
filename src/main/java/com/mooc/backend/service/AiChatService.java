package com.mooc.backend.service;

import com.mooc.backend.entity.ChatMessage;
import com.mooc.backend.entity.ChatSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 行程助手核心服务（change: ai-chat-core，tasks 3.1 起具备完整管线）。
 *
 * <p>装配态由 {@link ObjectProvider}{@code <ChatClient>} 表达：未装配（无 key / 开关关闭）时
 * {@code configured()} 为 false，对话请求直接产出降级错误（fail-closed）。
 *
 * <p>每轮对话流程：{@code ensureSession}（无感建会话）→ {@code appendUserMessage}
 * （user 消息**先落库**）→ 载入最近 N 条历史（含本轮 user）→ {@code .system(SYSTEM_PROMPT)}
 * + {@code .messages(...)} 组 prompt → {@code .stream().content()} 增量流出 →
 * 流完成后把完整 assistant 回答落库；流异常则不落 assistant、错误上抛（错误事件由上层发）。
 *
 * <p>工具已在装配期经 {@code defaultToolCallbacks} 挂到 ChatClient（天气/汇率，
 * 定义一次、同 JVM 直调），本类零额外接线。时间由 {@code Clock} 注入便于测试。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ChatSessionService chatSessionService;
    private final Clock clock;

    public AiChatService(ObjectProvider<ChatClient> chatClientProvider,
                         ChatSessionService chatSessionService,
                         Clock clock) {
        this.chatClientProvider = chatClientProvider;
        this.chatSessionService = chatSessionService;
        this.clock = clock;
    }

    /** 模型是否已装配（容器中存在 ChatClient bean）。 */
    public boolean configured() {
        return chatClientProvider.getIfAvailable() != null;
    }

    /**
     * 单轮对话流式入口。
     *
     * @param sessionId 会话标识（客户端生成的 UUID）
     * @param message   本轮用户消息
     * @return 助手回答的增量内容流；未装配时为降级错误流
     */
    public Flux<String> stream(String sessionId, String message) {
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            return Flux.error(new com.mooc.backend.exception.AiChatUnavailableException());
        }
        Instant now = clock.instant();
        ChatSession session = chatSessionService.ensureSession(sessionId, now);
        chatSessionService.appendUserMessage(session, message, now);

        // 最近 N 条（含本轮 user，已落库）转 Spring Message，保持对话时序
        List<Message> history = toSpringMessages(
                chatSessionService.loadRecentMessages(session, chatSessionService.contextWindow()));

        StringBuilder assistantSink = new StringBuilder();
        return client.prompt()
                .system(AiPrompts.SYSTEM_PROMPT)
                .messages(history)
                .stream()
                .content()
                .doOnNext(assistantSink::append)
                // 完成信号切到 boundedElastic：避免在 WebClient 事件循环线程同步跑 JDBC
                // 落库阻塞（review F3-1）。
                .publishOn(Schedulers.boundedElastic())
                .doOnComplete(() ->
                        persistAssistantSafely(session, assistantSink.toString()));
    }

    /**
     * assistant 全文落库，失败<b>只告警不转 error</b>（review F3-2）：回答已完整流出给
     * 客户端，落库失败不应让 UI 误删已交付内容；告警留痕、可后续重试/审计。
     */
    private void persistAssistantSafely(ChatSession session, String fullAnswer) {
        try {
            chatSessionService.appendAssistantMessage(session, fullAnswer, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Failed to persist assistant message for session {} (answer already delivered)",
                    session.getSessionId(), e);
        }
    }

    private List<Message> toSpringMessages(List<ChatMessage> persisted) {
        List<Message> out = new ArrayList<>(persisted.size());
        for (ChatMessage m : persisted) {
            out.add(m.getRole() == com.mooc.backend.entity.ChatMessageRole.USER
                    ? new UserMessage(m.getContent())
                    : new AssistantMessage(m.getContent()));
        }
        return out;
    }
}
