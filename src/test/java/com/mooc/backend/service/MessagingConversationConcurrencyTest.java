package com.mooc.backend.service;
import com.mooc.backend.service.MessagingService;

import com.mooc.backend.entity.User;
import com.mooc.backend.repository.UserRepository;
import com.mooc.backend.repository.ConversationRepository;
import com.mooc.backend.service.UuidOrdering;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发同对会话创建集成测试（Task 2.1 必测项，PRD 风险表点名）。
 *
 * <p>两个线程以 {@code CyclicBarrier} 对齐起点，对<b>同一对用户</b>并发发起会话：
 * 行锁串行化（按字节序低→高锁用户行）保证后到线程在先到线程提交后命中预查回读；
 * 唯一约束 {@code uk_conversations_pair} 兜底极端调度。断言不变量：
 * 双方拿到<b>同一</b>会话 id、恰好一方为「新建」、库中该有序对<b>恰一行</b>。
 *
 * <p>本类<b>不使用</b> {@code @Transactional}：跨线程碰撞需要真实提交（行锁持有至
 * commit），回滚式测试无法触发约束/锁路径。隔离以「每轮随机邮箱」保证，
 * 结束后按前缀清理真实提交的行。
 */
@SpringBootTest
class MessagingConversationConcurrencyTest {

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM messages").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM conversations").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM users WHERE email LIKE 'concurrency-%'").executeUpdate();
        });
    }

    @Test
    void concurrentCreationOfSamePairYieldsExactlyOneConversation() throws Exception {
        for (int round = 0; round < 5; round++) {
            assertSingleConversationForConcurrentCreation(round);
        }
    }

    private void assertSingleConversationForConcurrentCreation(int round) throws Exception {
        UUID aliceId = persistUser("concurrency-a-" + round + "-" + UUID.randomUUID() + "@example.com");
        UUID bobId = persistUser("concurrency-b-" + round + "-" + UUID.randomUUID() + "@example.com");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MessagingService.CreateConversationResult> initiator = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return template.execute(status ->
                        messagingService.createConversation(aliceId, bobId, Instant.now()));
            };

            Future<MessagingService.CreateConversationResult> first = pool.submit(initiator);
            Future<MessagingService.CreateConversationResult> second = pool.submit(initiator);

            MessagingService.CreateConversationResult r1 = first.get(30, TimeUnit.SECONDS);
            MessagingService.CreateConversationResult r2 = second.get(30, TimeUnit.SECONDS);

            // 不变量 1：双方拿到同一会话（幂等语义在并发下依然成立）
            assertThat(r1.conversation().getId())
                    .as("round %d: both threads must observe the same conversation", round)
                    .isEqualTo(r2.conversation().getId());
            // 不变量 2：恰好一方「新建」，另一方回读既有会话
            assertThat(r1.newlyCreated() ^ r2.newlyCreated())
                    .as("round %d: exactly one thread creates, the other re-reads", round)
                    .isTrue();
            // 不变量 3：唯一约束兜底——库中该有序对恰一行
            var pair = UuidOrdering.ordered(aliceId, bobId);
            assertThat(conversationRepository.countByUserLowIdAndUserHighId(pair.low(), pair.high()))
                    .as("round %d: exactly one row for the pair", round)
                    .isEqualTo(1);
            // 双方回读的会话即该唯一行
            assertThat(conversationRepository.findById(r1.conversation().getId())
                    .orElseThrow().getInitiatorId()).isIn(aliceId, bobId);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** 直接落库 EMAIL_UNVERIFIED 用户（service 不特判该状态；不消耗任何限流配额）。 */
    private UUID persistUser(String email) {
        User user = userRepository.save(User.register(email, "hash-" + email, "User-" + email, Instant.now()));
        return user.getId();
    }
}
