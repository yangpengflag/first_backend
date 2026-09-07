package com.mooc.backend.service;
import com.mooc.backend.service.ChatCleanupJob;
import com.mooc.backend.service.ChatSessionService;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匿名会话保留清理（tasks 6.1）：超保留期的会话及其消息被删除，期内不受影响。
 */
@SpringBootTest
@Transactional
class ChatCleanupJobTest {

    @Autowired
    ChatCleanupJob chatCleanupJob;
    @Autowired
    ChatSessionRepository chatSessionRepository;
    @Autowired
    ChatMessageRepository chatMessageRepository;
    @Autowired
    ChatSessionService chatSessionService;

    @BeforeEach
    void cleanAiTables() {
        chatMessageRepository.deleteAll();
        chatSessionRepository.deleteAll();
    }

    private ChatSession createSession(String sessionId, Instant createdAt) {
        ChatSession session = ChatSession.create(sessionId, null, createdAt);
        return chatSessionRepository.save(session);
    }

    @Test
    void expiredSessionsAndTheirMessagesArePurged() {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        ChatSession expired = createSession("00000000-0000-0000-0000-000000000001",
                now.minus(40, ChronoUnit.DAYS));
        chatSessionService.appendUserMessage(expired, "old q", now.minus(40, ChronoUnit.DAYS));
        chatSessionService.appendAssistantMessage(expired, "old a", now.minus(39, ChronoUnit.DAYS));

        int removed = chatCleanupJob.purgeExpired(now, 30);

        assertThat(removed).isEqualTo(1);
        assertThat(chatSessionRepository.findBySessionId("00000000-0000-0000-0000-000000000001"))
                .isEmpty();
        assertThat(chatMessageRepository.count()).isZero();
    }

    @Test
    void sessionsWithinRetentionWindowAreUntouched() {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        ChatSession fresh = createSession("00000000-0000-0000-0000-000000000002", now);
        chatSessionService.appendUserMessage(fresh, "new q", now);
        chatSessionService.appendAssistantMessage(fresh, "new a", now.plus(1, ChronoUnit.SECONDS));

        int removed = chatCleanupJob.purgeExpired(now, 30);

        assertThat(removed).isZero();
        assertThat(chatSessionRepository.findBySessionId("00000000-0000-0000-0000-000000000002"))
                .isPresent();
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }

    @Test
    void messageRolesRoundTripAsSaved() {
        // 保证 domain 映射在清洗链路可用（USER/ASSISTANT 落库读回）
        ChatSession s = chatSessionService.ensureSession("00000000-0000-0000-0000-000000000003",
                Instant.now());
        chatSessionService.appendUserMessage(s, "hi", Instant.now());
        assertThat(chatSessionService.loadRecentMessages(s, 5).get(0).getRole())
                .isEqualTo(ChatMessageRole.USER);
    }
}
