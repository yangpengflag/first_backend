package com.mooc.backend.service;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UUID 无符号字节序比较与有序对工具（Task 1.2，design.md「会话有序对」）。
 *
 * <p><b>禁用 {@link UUID#compareTo}</b>：它按 signed long 比较两个 64 位半段，
 * 负 msb（首字节 >= 0x80）的 UUID 会被排到所有正 msb 之后，与无符号字节序不一致。
 * 会话有序对 (user_low_id, user_high_id) 若误用它，同一对用户以不同方向发起会话
 * 会映射出不同的 (low, high)，幂等创建无法命中同一行（唯一约束虽兜底，
 * 但幂等请求会误报约束冲突）。
 *
 * <p>本工具按 {@code binary(16)} 存储布局（msb/lsb 大端拼接，与 Hibernate 6 一致）
 * 做<b>无符号字节</b>字典序比较，保证任意方向映射到同一个有序对。
 */
public final class UuidOrdering {

    private UuidOrdering() {
        // utility
    }

    /**
     * 无符号字节序比较：等价于两 UUID 的 16 字节大端表示的字典序
     * （亦等价于 {@code Long.compareUnsigned(msb)} 平 {@code lsb}）。
     */
    public static int compareUnsigned(UUID a, UUID b) {
        byte[] x = toBytes(a);
        byte[] y = toBytes(b);
        for (int i = 0; i < 16; i++) {
            int xi = x[i] & 0xFF;
            int yi = y[i] & 0xFF;
            if (xi != yi) {
                return xi < yi ? -1 : 1;
            }
        }
        return 0;
    }

    /** 返回 (low, high) 有序对：任意调用方向得到同一映射（双向一致）。 */
    public static OrderedPair ordered(UUID a, UUID b) {
        return compareUnsigned(a, b) <= 0 ? new OrderedPair(a, b) : new OrderedPair(b, a);
    }

    /** 会话有序对视图。 */
    public record OrderedPair(UUID low, UUID high) {
    }

    /** 编码为 16 字节大端，与 Hibernate 6 的 {@code binary(16)} 存储布局一致。 */
    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
