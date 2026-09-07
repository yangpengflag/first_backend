package com.mooc.backend.service;
import com.mooc.backend.service.UuidOrdering;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UUID 有序对工具单元测试（Task 1.2）。
 *
 * <p>核心回归点：负 msb（首字节 >= 0x80）的 UUID 在 {@link UUID#compareTo} 的
 * signed long 语义下与无符号字节序<b>不一致</b>——若误用会导致 (A,B) 与 (B,A)
 * 映射出不同的 (low, high)，同一对用户双向建会话无法命中同一行
 * （唯一约束虽兜底，但幂等请求会误报冲突）。
 *
 * <p>参考实现独立于被测代码：UUID 的 16 字节为大端 msb/lsb 拼接，
 * 无符号字节序字典序等价于 {@code Long.compareUnsigned(msb)} 平 {@code lsb}。
 */
class UuidOrderingTest {

    /** msb 为负（首字节 0xff）的 UUID：UUID.compareTo 把它排在所有正 msb 之后。 */
    private static final UUID NEG_MSB = UUID.fromString("ff1d4fae-7dec-11d0-a765-00a0c91e6bf6");

    /** msb 为正（首字节 0x0f）的 UUID。 */
    private static final UUID POS_MSB = UUID.fromString("0f1d4fae-7dec-11d0-a765-00a0c91e6bf6");

    // ---------- 与 UUID.compareTo 的语义差异 ----------

    @Test
    void compareUnsignedOrdersNegativeMsbUuidsAfterPositiveOnes() {
        // 无符号字节序：0x0f... < 0xff...
        assertThat(UuidOrdering.compareUnsigned(POS_MSB, NEG_MSB)).isNegative();
        assertThat(UuidOrdering.compareUnsigned(NEG_MSB, POS_MSB)).isPositive();
        assertThat(UuidOrdering.compareUnsigned(NEG_MSB, NEG_MSB)).isZero();
    }

    @Test
    void javaUuidCompareToDisagreesOnNegativeMsb_documentingThePitfall() {
        // signed long 语义给出相反结论——这正是禁用 UUID.compareTo 的原因
        assertThat(POS_MSB.compareTo(NEG_MSB)).isPositive();
    }

    // ---------- 双向一致（design.md 必测项） ----------

    @Test
    void orderedMapsNegativeMsbPairIdenticallyInBothDirections() {
        UuidOrdering.OrderedPair ab = UuidOrdering.ordered(NEG_MSB, POS_MSB);
        UuidOrdering.OrderedPair ba = UuidOrdering.ordered(POS_MSB, NEG_MSB);

        assertThat(ab.low()).isEqualTo(POS_MSB);
        assertThat(ab.high()).isEqualTo(NEG_MSB);
        assertThat(ba.low()).isEqualTo(ab.low());
        assertThat(ba.high()).isEqualTo(ab.high());
    }

    @Test
    void orderedIsIdentityForSameUuid() {
        UuidOrdering.OrderedPair pair = UuidOrdering.ordered(NEG_MSB, NEG_MSB);

        assertThat(pair.low()).isEqualTo(NEG_MSB);
        assertThat(pair.high()).isEqualTo(NEG_MSB);
    }

    // ---------- 随机 UUID 对照独立参考实现 ----------

    @Test
    void orderedMatchesIndependentUnsignedReferenceOnRandomUuids() {
        for (int i = 0; i < 200; i++) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();

            UuidOrdering.OrderedPair forward = UuidOrdering.ordered(a, b);
            UuidOrdering.OrderedPair backward = UuidOrdering.ordered(b, a);

            // 双向映射必须一致
            assertThat(forward.low()).as("low of %s/%s", a, b).isEqualTo(backward.low());
            assertThat(forward.high()).as("high of %s/%s", a, b).isEqualTo(backward.high());

            // 与独立参考实现一致
            int c = referenceCompare(a, b);
            UUID expectedLow = c <= 0 ? a : b;
            UUID expectedHigh = c <= 0 ? b : a;
            assertThat(forward.low()).isEqualTo(expectedLow);
            assertThat(forward.high()).isEqualTo(expectedHigh);
        }
    }

    @Test
    void compareUnsignedMatchesIndependentReferenceOnRandomUuids() {
        for (int i = 0; i < 200; i++) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();

            int actual = UuidOrdering.compareUnsigned(a, b);
            int expected = referenceCompare(a, b);

            assertThat(Integer.signum(actual)).as("%s vs %s", a, b).isEqualTo(Integer.signum(expected));
        }
    }

    /** 独立参考实现：无符号 long 平比较（与 16 字节大端无符号字典序等价）。 */
    private static int referenceCompare(UUID a, UUID b) {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        if (c != 0) {
            return c;
        }
        return Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
