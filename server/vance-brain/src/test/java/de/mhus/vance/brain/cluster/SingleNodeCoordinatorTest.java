package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SingleNodeCoordinatorTest {

    private final SingleNodeCoordinator coordinator = new SingleNodeCoordinator();

    @Test
    void thePodIsAlwaysTheCoordinator() {
        assertThat(coordinator.isLocalPodMaster()).isTrue();
        assertThat(coordinator.isCoordinated()).isTrue();
    }

    @Test
    void bothKindsOfWorkRun() {
        // Housekeeping sweeps and writes into a foreign system alike — the
        // community brain has to run every job the cloud runs on one pod.
        assertThat(coordinator.runsIdempotentSweeps()).isTrue();
        assertThat(coordinator.runsForeignWrites()).isTrue();
    }

    @Test
    void thereIsNoLease() {
        assertThat(coordinator.currentLease()).isEmpty();
    }
}
