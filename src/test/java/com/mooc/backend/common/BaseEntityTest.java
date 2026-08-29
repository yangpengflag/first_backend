package com.mooc.backend.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaseEntityTest {

    /** 具体子类，用于测试抽象 BaseEntity 的行为。 */
    static class Dummy extends BaseEntity {
        protected Dummy() {
            super();
        }

        Dummy(UUID id, Instant now) {
            super(id, now);
        }
    }

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void constructsWithIdAndTimestamps() {
        UUID id = UUID.randomUUID();
        Dummy d = new Dummy(id, T0);
        assertEquals(id, d.getId());
        assertEquals(T0, d.getCreatedAt());
        assertEquals(T0, d.getUpdatedAt());
        assertNull(d.getDeletedAt());
    }

    @Test
    void touchRefreshesUpdatedAtOnly() {
        Dummy d = new Dummy(UUID.randomUUID(), T0);
        d.touch(T1);
        assertEquals(T1, d.getUpdatedAt());
        assertEquals(T0, d.getCreatedAt());
    }

    @Test
    void equalsAndHashCodeByClassAndId() {
        UUID id = UUID.randomUUID();
        Dummy a = new Dummy(id, T0);
        Dummy b = new Dummy(id, T0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        Dummy c = new Dummy(UUID.randomUUID(), T0);
        assertNotEquals(a, c);
        assertNotEquals(a, "not an entity");
        assertNotEquals(a, null);
    }
}
