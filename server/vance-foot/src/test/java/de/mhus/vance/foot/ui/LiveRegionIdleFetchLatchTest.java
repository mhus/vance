package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.foot.config.FootConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The idle-suggestion trigger fires once per idle period, not once per
 * animator tick. The animator ticks ~8×/s for as long as the user stays
 * idle, and every fetch outcome that leaves no visible suggestion (none
 * offered, already accepted, REST failure) keeps the idle condition
 * true — so without this latch the provider was called, and a failing
 * brain re-requested, several times a second.
 */
class LiveRegionIdleFetchLatchTest {

    private LiveRegion region;

    @BeforeEach
    void setUp() {
        region = new LiveRegion(
                mock(StatusBar.class), mock(FootConfig.class), mock(ColorResolver.class));
    }

    @Test
    void firstClaim_isGranted() {
        assertThat(region.claimIdleFetch(1_000L, 0L)).isTrue();
    }

    @Test
    void repeatedClaims_forTheSameStateAreRefused() {
        region.claimIdleFetch(1_000L, 0L);

        assertThat(region.claimIdleFetch(1_000L, 0L)).isFalse();
        assertThat(region.claimIdleFetch(1_000L, 0L)).isFalse();
    }

    @Test
    void newInputActivity_reArmsTheLatch() {
        region.claimIdleFetch(1_000L, 0L);

        assertThat(region.claimIdleFetch(2_500L, 0L)).isTrue();
    }

    @Test
    void newAssistantMessage_reArmsTheLatchWithoutInputActivity() {
        // The user sits still while the brain replies: the activity
        // timestamp doesn't move, so only the provider generation can
        // tell the animator that a fresh fetch is warranted.
        region.claimIdleFetch(1_000L, 7L);

        assertThat(region.claimIdleFetch(1_000L, 8L)).isTrue();
    }

    @Test
    void reArmedLatch_isConsumedAgainAfterOneClaim() {
        region.claimIdleFetch(1_000L, 0L);
        region.claimIdleFetch(2_000L, 0L);

        assertThat(region.claimIdleFetch(2_000L, 0L)).isFalse();
    }
}
