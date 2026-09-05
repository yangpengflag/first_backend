package com.mooc.backend.messaging.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 一对一会话实体（Task 1.1，继承 {@code BaseEntity} 软删内核）。
 *
 * <p><b>有序对</b>：同一对用户无论谁发起，都映射到同一行——
 * {@code user_low_id} / {@code user_high_id} 按 {@link UuidOrdering} 的无符号字节序排列，
 * 由唯一约束 {@code uk_conversations_pair} 兜底幂等；{@code initiator_id} 记录发起方，
 * 可见性延迟规则（无消息会话仅发起者可见）依赖该列。
 *
 * <p><b>冗余列</b>：{@code last_message_id} / {@code last_message_at} 随消息发送同事务刷新，
 * 会话列表排序与展示直接读本列，避免每会话一次 messages 反查。
 * {@code last_message_id IS NULL} 即空会话（尚未发出首条消息）。
 *
 * <p><b>索引</b>：{@code idx_conversations_low (user_low_id, last_message_at)}、
 * {@code idx_conversations_high (user_high_id, last_message_at)} 服务两个方向的
 * 会话列表查询（design.md）。固定两人会话，不建 members 关联表（YAGNI）。
 */
@Entity
@Table(name = "conversations",
        uniqueConstraints = @UniqueConstraint(name = "uk_conversations_pair",
                columnNames = {"user_low_id", "user_high_id"}),
        indexes = {
                @Index(name = "idx_conversations_low", columnList = "user_low_id, last_message_at"),
                @Index(name = "idx_conversations_high", columnList = "user_high_id, last_message_at")
        })
public class Conversation extends BaseEntity {

    /** 有序对中较小的一方（无符号字节序）。 */
    @Column(name = "user_low_id", nullable = false)
    private UUID userLowId;

    /** 有序对中较大的一方（无符号字节序）。 */
    @Column(name = "user_high_id", nullable = false)
    private UUID userHighId;

    /** 会话发起方：可见性延迟规则依赖（空会话仅发起者可见）。 */
    @Column(name = "initiator_id", nullable = false)
    private UUID initiatorId;

    /** 最后一条消息 id；null 表示空会话（可见性延迟窗口内）。 */
    @Column(name = "last_message_id")
    private UUID lastMessageId;

    /** 最后一条消息时刻；会话列表排序与条目展示时间取本列。 */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    protected Conversation() {
        // JPA only
    }

    private Conversation(UUID id, UUID userLowId, UUID userHighId, UUID initiatorId, Instant now) {
        super(id, now);
        this.userLowId = userLowId;
        this.userHighId = userHighId;
        this.initiatorId = initiatorId;
    }

    /** 创建会话：有序对由 {@link UuidOrdering} 计算，保证双向映射一致。 */
    public static Conversation create(UUID initiatorId, UUID recipientId, Instant now) {
        if (initiatorId.equals(recipientId)) {
            throw new IllegalArgumentException("A conversation requires two distinct users.");
        }
        UuidOrdering.OrderedPair pair = UuidOrdering.ordered(initiatorId, recipientId);
        return new Conversation(UUID.randomUUID(), pair.low(), pair.high(), initiatorId, now);
    }

    /** 当前用户是否为会话成员（两固定成员之一）。 */
    public boolean isMember(UUID userId) {
        return userLowId.equals(userId) || userHighId.equals(userId);
    }

    /** 成员userId 的对方 id；非成员调用抛出（调用方须先经 {@link #isMember} 判定）。 */
    public UUID otherMemberId(UUID userId) {
        if (userLowId.equals(userId)) {
            return userHighId;
        }
        if (userHighId.equals(userId)) {
            return userLowId;
        }
        throw new IllegalStateException("User " + userId + " is not a member of conversation " + getId());
    }

    /**
     * 发送消息后刷新冗余列：{@code last_message_*} 与审计时间同事务更新
     * （调用方保证与消息落库同事务）。
     */
    public void applyLastMessage(UUID messageId, Instant messageAt, Instant now) {
        this.lastMessageId = messageId;
        this.lastMessageAt = messageAt;
        touch(now);
    }

    public UUID getUserLowId() {
        return userLowId;
    }

    public UUID getUserHighId() {
        return userHighId;
    }

    public UUID getInitiatorId() {
        return initiatorId;
    }

    public UUID getLastMessageId() {
        return lastMessageId;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    /** 空会话判定：尚未发出任何消息（可见性延迟窗口内）。 */
    public boolean isEmpty() {
        return lastMessageId == null;
    }
}
