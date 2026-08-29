package com.mooc.backend.auth.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserStatusTest {

    /** 状态取值域必须严格为四态，不得增删。 */
    @Test
    void containsExactlyFourStates() {
        assertThat(UserStatus.values()).hasSize(4);
        assertThat(UserStatus.values()).containsExactlyInAnyOrder(
                UserStatus.ACTIVE,
                UserStatus.LOCKED,
                UserStatus.DELETED,
                UserStatus.EMAIL_UNVERIFIED
        );
    }

    @Test
    void everyStateHasExpectedName() {
        assertThat(UserStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(UserStatus.LOCKED.name()).isEqualTo("LOCKED");
        assertThat(UserStatus.DELETED.name()).isEqualTo("DELETED");
        assertThat(UserStatus.EMAIL_UNVERIFIED.name()).isEqualTo("EMAIL_UNVERIFIED");
    }
}
