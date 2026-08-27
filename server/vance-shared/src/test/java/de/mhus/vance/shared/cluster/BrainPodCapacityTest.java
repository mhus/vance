package de.mhus.vance.shared.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The two capacity layers and their precedence — the only reader of both. */
class BrainPodCapacityTest {

    private static BrainPodDocument pod(int current, int max, Integer override) {
        return BrainPodDocument.builder()
                .nodeName("p")
                .resourcesCurrentScore(current)
                .resourcesMaxScore(max)
                .resourcesMaxScoreOverride(override)
                .build();
    }

    @Test
    void withoutAnOverride_theConfiguredValueDecides() {
        assertThat(BrainPodCapacity.effectiveMaxScore(pod(0, 10000, null))).isEqualTo(10000);
        assertThat(BrainPodCapacity.isOverridden(pod(0, 10000, null))).isFalse();
    }

    @Test
    void anOverrideWins_andSaysSo() {
        BrainPodDocument p = pod(0, 10000, 400);

        assertThat(BrainPodCapacity.effectiveMaxScore(p)).isEqualTo(400);
        assertThat(BrainPodCapacity.isOverridden(p))
                .as("a cap that quietly returns to its configured value on the next "
                        + "re-registration has to be distinguishable from a configured one")
                .isTrue();
    }

    @Test
    void anOverrideMayRaiseTheCapToo() {
        // Recalibration, not throttling: the configured value may have been set
        // too low, which is the case this whole field exists for.
        assertThat(BrainPodCapacity.effectiveMaxScore(pod(0, 100, 5000))).isEqualTo(5000);
    }

    @Test
    void unsetConfiguredValue_isClampedToOneRatherThanReadingAsFull() {
        // An old or hand-written row with maxScore=0 would otherwise mean
        // "fits nothing", which looks like a full pod — the harder of the two
        // failures to diagnose.
        assertThat(BrainPodCapacity.effectiveMaxScore(pod(0, 0, null))).isEqualTo(1);
    }

    @Test
    void headroom_isNegativeWhenOverbooked() {
        assertThat(BrainPodCapacity.headroom(pod(120, 100, null)))
                .as("overbooking is a legitimate state — the cap has always been best-effort")
                .isEqualTo(-20);
    }

    @Test
    void headroomAndLoadFraction_bothFollowTheOverride() {
        BrainPodDocument p = pod(50, 10000, 100);

        assertThat(BrainPodCapacity.headroom(p)).isEqualTo(50);
        assertThat(BrainPodCapacity.loadFraction(p))
                .as("the sort key has to use the same cap the fit check uses, or the "
                        + "cheapest-looking pod is not the one with room")
                .isEqualTo(0.5);
    }
}
