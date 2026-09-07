package com.mooc.backend.ai.service;

import com.mooc.backend.ai.config.AiChatProperties;
import com.mooc.backend.ai.domain.ChatSession;
import com.mooc.backend.ai.repository.ChatMessageRepository;
import com.mooc.backend.ai.repository.ChatSessionRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 匿名会话保留清理（change: ai-chat-core，tasks 6.1）。
 *
 * <p>游客会话不可绑定账号，若不清理会无限膨胀：按会话 {@code createdAt} 保留
 * {@code app.ai-chat.retention-days}（默认 30）天后删除会话及其全部消息。幂等、无账号配合
 * （spec「匿名消息保留与清理」）。清理为物理删（软删语义不适用生命周期清理）。
 *
 * <p>{@code purgeExpired} 可手动触发便于测试；{@code scheduledPurge} 经既有
 * {@code @EnableScheduling} 每日执行（cron 可配，默认 04:30）。
 */
@Service
public class ChatCleanupJob {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AiChatProperties properties;
    private final Clock clock;

    public ChatCleanupJob(ChatSessionRepository chatSessionRepository,
                          ChatMessageRepository chatMessageRepository,
                          AiChatProperties properties, Clock clock) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 删除保留期前的会话及其消息；返回删除的会话数（幂等）。 */
    @Transactional
    public int purgeExpired(Instant now, int retentionDays) {
        // 注：@Transactional 在 bean 代理上生效。scheduledPurge 经由调度器调用的是
        // 代理对象，其自身事务与内部 this.purgeExpired 的 REQUIRED 传播合并为同一事务，
        // 保证「删消息 + 删会话」整批原子（自调用不绕过代理）。
        // 单测直接调 purgeExpired 亦经代理获得独立事务。
        Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
        List<ChatSession> expired = chatSessionRepository.findByCreatedAtBefore(cutoff);
        int removed = 0;
        for (ChatSession session : expired) {
            chatMessageRepository.deleteBySession_Id(session.getId());
            chatSessionRepository.delete(session);
            removed++;
        }
        return removed;
    }

    @Scheduled(cron = "${app.ai-chat.cleanup-cron:0 30 4 * * *}")
    @Transactional
    public void scheduledPurge() {
        // 入口方法带事务（经代理调用）：让内部 this.purgeExpired 的删除在同一事务内原子。
        purgeExpired(clock.instant(), properties.retentionDays());
    }
}
