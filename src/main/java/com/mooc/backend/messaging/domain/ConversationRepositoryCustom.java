package com.mooc.backend.messaging.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话列表游标分页查询（Task 1.1）。
 *
 * <p>排序键为 {@code COALESCE(last_message_at, created_at)} 倒序（空会话以创建时间参与全序，
 * 保证游标语义良定义），tie-break 按 id 字节序（与 Hibernate 6 {@code binary(16)}
 * 存储布局一致，见 {@code NotificationRepositoryImpl} 同款约束）。
 * 可见性延迟过滤（无消息会话仅发起者可见）内建于查询。
 */
public interface ConversationRepositoryCustom {

    /**
     * 按当前用户可见的会话取一页。
     *
     * @param userId    当前用户（数据隔离与可见性边界）
     * @param limit     返回上限（调用方传 size+1 以探测 has_more）
     * @param cursorTs  游标时间戳（useCursor=false 时忽略）
     * @param cursorId  游标 id（useCursor=false 时忽略）
     * @param useCursor true 时启用游标截断
     */
    List<Conversation> findPage(UUID userId, int limit, Instant cursorTs, UUID cursorId, boolean useCursor);
}
