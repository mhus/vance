package de.mhus.vance.shared.user.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The tombstone exists to stop a re-created login from inheriting somebody
 * else's history. Both properties below are load-bearing for that.
 */
class UserTombstoneTest {

    @Test
    void of_marksTheNameAsBelongingToNobody() {
        assertThat(UserTombstone.of("mhus")).isEqualTo("_deleted_mhus");
    }

    @Test
    void of_isIdempotent_soARerunDoesNotStack() {
        // A delete that half-ran is re-run by the operator; without this the
        // second pass would produce `_deleted__deleted_mhus` and detach the
        // history from the name a second time.
        assertThat(UserTombstone.of(UserTombstone.of("mhus"))).isEqualTo("_deleted_mhus");
    }

    @Test
    void isTombstone_recognisesWhatItProduced() {
        assertThat(UserTombstone.isTombstone(UserTombstone.of("mhus"))).isTrue();
        assertThat(UserTombstone.isTombstone("mhus")).isFalse();
        // A service account is not a tombstone, although both start with '_'.
        assertThat(UserTombstone.isTombstone("_trillian-void-a7f3")).isFalse();
    }
}
