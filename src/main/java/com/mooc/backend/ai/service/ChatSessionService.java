package com.mooc.backend.ai.service;

import com.mooc.backend.ai.config.AiChatProperties;
import com.mooc.backend.ai.domain.ChatMessage;
import com.mooc.backend.ai.domain.ChatMessageRole;
import com.mooc.backend.ai.domain.ChatSession;
import com.mooc.backend.ai.repository.ChatMessageRepository;
import com.mooc.backend.ai.repository.ChatSessionRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 会话与消息持久化服务（change: ai-chat-core，tasks 2.2）。
 *
 * <p>落库时机：user 消息**先落库再调模型**（生成失败用户的话也不丢）；assistant 完整回答
 * 生成结束后落库（不逐 token 写）。多轮上下文由 {@link #loadRecentMessages} 载入该会话
 * 最近 N 条（升序、含超窗裁最旧），以服务端持久化数据为准。
 */
@Service
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AiChatProperties aiChatProperties;

    public ChatSessionService(ChatSessionRepository chatSessionRepository,
                              ChatMessageRepository chatMessageRepository,
                              AiChatProperties aiChatProperties) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.aiChatProperties = aiChatProperties;
    }

    /** 取或建会话（无感创建，游客 owner 恒 null）。 */
    @Transactional
    public ChatSession ensureSession(String sessionId, Instant now) {
        return chatSessionRepository.findBySessionId(sessionId)
                .orElseGet(() -> chatSessionRepository.save(
                        ChatSession.create(sessionId, null, now)));
    }

    /** user 消息先落库（在调模型之前调用）。 */
    @Transactional
    public ChatMessage appendUserMessage(ChatSession session, String content, Instant now) {
        return chatMessageRepository.save(
                ChatMessage.create(session, ChatMessageRole.USER, content, now));
    }

    /** assistant 完整回答落库（流结束后调用一次）。 */
    @Transactional
    public ChatMessage appendAssistantMessage(ChatSession session, String content, Instant now) {
        return chatMessageRepository.save(
                ChatMessage.create(session, ChatMessageRole.ASSISTANT, content, now));
    }

    /**
     * 载入会话最近 n 条消息（升序、对话语义顺序）。仓库按新→旧取前 n 后在此反转；
     * 多于 n 时最旧被裁掉（超窗，控制 token 预算）。
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> loadRecentMessages(ChatSession session, int limit) {
        List<ChatMessage> recentDesc = chatMessageRepository
                .findBySession_IdAndDeletedFalseOrderByCreatedAtDescIdDesc(
                        session.getId(), PageRequest.of(0, limit));
        List<ChatMessage> ascending = new ArrayList<>(recentDesc);
        Collections.reverse(ascending);
        return ascending;
    }

    /** 默认上下文窗口条数（来自配置）。 */
    public int contextWindow() {
        return aiChatProperties.contextWindow();
    }
}
