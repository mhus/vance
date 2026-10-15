package de.mhus.vance.brain.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The single liveness criterion of sweeper, dispatcher and operator view:
 * project ownership's endpoint check, and this pod is always alive.
 */
class LeasePodLivenessTest {

    private static final String SELF = "pod-self";

    private ClusterService cluster;

    @BeforeEach
    void setUp() {
        cluster = mock(ClusterService.class);
        when(cluster.selfPodId()).thenReturn(SELF);
        when(cluster.resolveEndpointByPodId(any())).thenReturn(Optional.empty());
    }

    @Test
    void ownPod_isAlive_evenWithoutAResolvableEndpoint() {
        assertThat(LeasePodLiveness.isAlive(cluster, SELF)).isTrue();
    }

    @Test
    void foreignPod_isAlive_onlyWithAnEndpoint() {
        assertThat(LeasePodLiveness.isAlive(cluster, "pod-gone")).isFalse();

        when(cluster.resolveEndpointByPodId("pod-other")).thenReturn(Optional.of("http://other"));
        assertThat(LeasePodLiveness.isAlive(cluster, "pod-other")).isTrue();
    }
}
