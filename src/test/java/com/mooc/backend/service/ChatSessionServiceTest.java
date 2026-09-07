package com.mooc.backend.service;
import com.mooc.backend.service.ChatSessionService;

import com.mooc.backend.entity.ChatMessage;
import com.mooc.backend.entity.ChatMessageRole;
import com.mooc.backend.entity.ChatSession;
import com.mooc.backend.repository.ChatMessageRepository;
import com.mooc.backend.repository.ChatSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话/消息持久化与多轮上下文（tasks 2.1 / 2.2）。@SpringBootTest + @Transactional：
 * 每个用例内回滚，不破坏本地真实数据（照 PostRepositoryTest 先例）。
 */
@SpringBootTest
@Transactional
class ChatSessionServiceTest {

    @Autowired
    ChatSessionService chatSessionService;
    @Autowired
    ChatSessionRepository chatSessionRepository;
    @Autowired
    ChatMessageRepository chatMessageRepository;

    @BeforeEach
    void cleanAiTables() {
        chatMessageRepository.deleteAll();
        chatSessionRepository.deleteAll();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void ensureSessionCreatesThenReusesSameIdentity() {
        String sessionId = uuid();
        Instant t0 = Instant.now();

        ChatSession first = chatSessionService.ensureSession(sessionId, t0);
        ChatSession again = chatSessionService.ensureSession(sessionId, t0.plus(1, ChronoUnit.MINUTES));

        assertThat(first.getId()).isEqualTo(again.getId());
        assertThat(chatSessionRepository.findBySessionId(sessionId)).isPresent();
        // 游客会话属主恒 null（预留列不写入）
        assertThat(first.getOwnerUserId()).isNull();
    }

    @Test
    void distinctSessionIdsCreateDistinctSessions() {
        ChatSession a = chatSessionService.ensureSession(uuid(), Instant.now());
        ChatSession b = chatSessionService.ensureSession(uuid(), Instant.now());

        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    void appendKeepsUserAssistantOrdering() {
        ChatSession s = chatSessionService.ensureSession(uuid(), Instant.now());
        chatSessionService.appendUserMessage(s, "hi", Instant.now());
        chatSessionService.appendAssistantMessage(s, "hello traveler", Instant.now().plus(1, ChronoUnit.SECONDS));

        List<ChatMessage> recent = chatSessionService.loadRecentMessages(s, 10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(recent.get(0).getContent()).isEqualTo("hi");
        assertThat(recent.get(1).getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(recent.get(1).getContent()).isEqualTo("hello traveler");
    }

    @Test
    void loadRecentCapsAtWindowKeepingNewestAscending() {
        ChatSession s = chatSessionService.ensureSession(uuid(), Instant.now());
        Instant base = Instant.now();
        for (int i = 1; i <= 5; i++) {
            chatSessionService.appendUserMessage(s, "m" + i, base.plus(i, ChronoUnit.SECONDS));
        }

        List<ChatMessage> recent = chatSessionService.loadRecentMessages(s, 3);

        assertThat(recent).extracting(ChatMessage::getContent).containsExactly("m3", "m4", "m5");
    }
}
