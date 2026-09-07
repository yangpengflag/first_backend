package com.mooc.backend.repository;
import com.mooc.backend.entity.Conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 会话仓储（Task 1.1）。
 *
 * <p>幂等预查按有序对 (user_low_id, user_high_id) 定位，命中唯一约束
 * {@code uk_conversations_pair} 的语义行；列表分页与未读聚合见
 * {@link ConversationRepositoryCustom} 的 native 实现。
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID>, ConversationRepositoryCustom {

    /** 按有序对定位既有会话（幂等创建的预查路径）。 */
    Optional<Conversation> findByUserLowIdAndUserHighId(UUID userLowId, UUID userHighId);

    /** 按有序对计数（并发兜底路径与测试断言用）。 */
    long countByUserLowIdAndUserHighId(UUID userLowId, UUID userHighId);
}
