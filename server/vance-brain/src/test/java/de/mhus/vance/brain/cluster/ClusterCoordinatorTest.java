package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The distinction the callers rely on: "not the master" and "nobody coordinates" are different. */
class ClusterCoordinatorTest {

    @Test
    void theElectedPod_runsEverything() {
        FakeCoordinator c = FakeCoordinator.master();
        assertThat(c.runsIdempotentSweeps()).isTrue();
        assertThat(c.runsForeignWrites()).isTrue();
    }

    @Test
    void aFollower_runsNothing() {
        FakeCoordinator c = FakeCoordinator.notMaster();
        assertThat(c.runsIdempotentSweeps()).isFalse();
        assertThat(c.runsForeignWrites()).isFalse();
    }

    @Test
    void withTheElectionOff_everyPodSweeps_butNoneWritesAbroad() {
        FakeCoordinator c = FakeCoordinator.uncoordinated();
        assertThat(c.runsIdempotentSweeps()).isTrue();
        assertThat(c.runsForeignWrites()).isFalse();
    }
}
