package com.mooc.backend.repository;
import com.mooc.backend.entity.Notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通知列表游标分页查询（Task 1.1）。
 *
 * <p>排序键 {@code (last_interacted_at, id)} 倒序；游标截断谓词
 * {@code (ts, id) < (cursorTs, cursorId)}，id 比较统一按 {@code binary(16)}
 * 字节序（与 Hibernate 6 存储布局一致，见 {@code PostRepositoryImpl} 同款约束），
 * 由实现侧把 cursorId 转为 16 字节传入。
 */
public interface NotificationRepositoryCustom {

    /**
     * 按 recipient 取一页通知。
     *
     * @param recipientId 接收者（数据隔离边界）
     * @param unreadOnly  true 时仅返回未读（read_at IS NULL）
     * @param limit       返回上限（调用方传 size+1 以探测 has_more）
     * @param cursorTs    游标时间戳（useCursor=false 时忽略）
     * @param cursorId    游标 id（useCursor=false 时忽略）
     * @param useCursor   true 时启用游标截断
     */
    List<Notification> findPage(UUID recipientId, boolean unreadOnly, int limit,
                                Instant cursorTs, UUID cursorId, boolean useCursor);
}
