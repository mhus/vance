package de.mhus.vance.toolpack.jaglan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import de.mhus.vance.api.documents.MountAccess;
import org.junit.jupiter.api.Test;

class JaglanCapabilitiesTest {

    private static JaglanCapabilities withTtl(Duration ttl) {
        return new JaglanCapabilities(MountAccess.RO, false, null, ttl, null, null);
    }

    @Test
    void missingTtl_fallsBackToTheDefault() {
        assertThat(withTtl(null).metadataTtl()).isEqualTo(JaglanCapabilities.DEFAULT_TTL);
    }

    @Test
    void zeroTtl_isClampedToTheFloorNotFoldedIntoTheDefault() {
        // The asymmetry with FeedCapabilities is deliberate: there zero folds
        // into the 30-minute default, which here would mean a source asking
        // for no caching gets cached for the default interval — a mistake
        // that never looks like one.
        JaglanCapabilities caps = withTtl(Duration.ZERO);

        assertThat(caps.metadataTtl()).isEqualTo(JaglanCapabilities.MIN_TTL);
        assertThat(caps.metadataTtl()).isNotEqualTo(JaglanCapabilities.DEFAULT_TTL);
    }

    @Test
    void negativeTtl_fallsBackToTheDefault() {
        // Negative is not a statement, it is nonsense — unlike zero.
        assertThat(withTtl(Duration.ofSeconds(-5)).metadataTtl())
                .isEqualTo(JaglanCapabilities.DEFAULT_TTL);
    }

    @Test
    void subFloorTtl_isRaisedToTheFloor() {
        assertThat(withTtl(Duration.ofSeconds(1)).metadataTtl())
                .isEqualTo(JaglanCapabilities.MIN_TTL);
    }

    @Test
    void statedTtlAboveTheFloor_isKept() {
        assertThat(withTtl(Duration.ofMinutes(42)).metadataTtl())
                .isEqualTo(Duration.ofMinutes(42));
    }

    @Test
    void nullAccess_becomesUnknownRatherThanAssumingWrite() {
        JaglanCapabilities caps = new JaglanCapabilities(null, false, null, null, null, null);

        assertThat(caps.access()).isEqualTo(MountAccess.UNKNOWN);
    }

    @Test
    void negativeItemCount_becomesUnknownNotZero() {
        // Zero would read as "empty folder" in the tree; unknown shows no
        // number at all.
        JaglanCapabilities caps = new JaglanCapabilities(
                MountAccess.RO, false, -1L, null, null, null);

        assertThat(caps.itemCount()).isNull();
    }

    @Test
    void zeroItemCount_isKeptBecauseAnEmptySourceIsAFact() {
        JaglanCapabilities caps = new JaglanCapabilities(
                MountAccess.RO, false, 0L, null, null, null);

        assertThat(caps.itemCount()).isZero();
    }

    @Test
    void nonPositiveMaxBytes_becomesNoStatedLimit() {
        assertThat(new JaglanCapabilities(MountAccess.RO, false, null, null, 0L, null).maxBytes())
                .isNull();
    }

    @Test
    void factories_setTheExpectedAccess() {
        assertThat(JaglanCapabilities.readOnly().access()).isEqualTo(MountAccess.RO);
        assertThat(JaglanCapabilities.readWrite().access()).isEqualTo(MountAccess.RW);
    }
}
