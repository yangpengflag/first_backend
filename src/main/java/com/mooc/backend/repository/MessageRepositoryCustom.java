package com.mooc.backend.repository;
import com.mooc.backend.entity.Message;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息历史与未读聚合查询（Task 1.1）。
 *
 * <p>历史分页为<b>倒查</b>：{@code (created_at, id)} 倒序取最新 N 条，由服务层内存反转为
 * 旧→新呈现；id 比较按 {@code binary(16)} 字节序（与 Hibernate 6 存储布局一致）。
 *
 * <p>未读统计按 conversations 冗余方向（user_low_id / user_high_id）拆为两条同构子查询
 * 分别命中 {@code idx_conversations_low / idx_conversations_high}，消息计数走
 * {@code idx_messages_conversation_created} 前缀——同一用户只会出现在某会话的
 * 一个方向（low ≠ high），UNION ALL 不产生重复计数（design.md「未读计数」）。
 */
public interface MessageRepositoryCustom {

    /**
     * 按会话取一页消息（倒查，新→旧返回，服务层负责反转）。
     *
     * @param conversationId 会话 id（数据隔离边界，调用方先做成员校验）
     * @param limit          返回上限（调用方传 size+1 以探测 has_more_earlier）
     * @param cursorTs       before 游标时间戳（useCursor=false 时忽略）
     * @param cursorId       before 游标 id（useCursor=false 时忽略）
     * @param useCursor      true 时启用游标截断
     */
    List<Message> findPage(UUID conversationId, int limit, Instant cursorTs, UUID cursorId, boolean useCursor);

    /**
     * 当前用户各会话的未读消息数（他人发出且 read_at IS NULL），按会话分组。
     * 会话列表 unread_count 的单条聚合数据源（避免每会话一次 count 的 N+1）。
     */
    Map<UUID, Long> countUnreadByConversation(UUID userId);

    /** 当前用户全部会话的未读消息总数（badge 数据源，走索引）。 */
    long countUnreadTotal(UUID userId);
}
