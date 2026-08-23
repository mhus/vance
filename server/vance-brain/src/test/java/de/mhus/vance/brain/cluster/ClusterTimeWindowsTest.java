package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The relationship between the cluster's time windows — specifically that a
 * derived routing answer is bounded by the <em>shorter</em> of the two gates it
 * came from, not by the lease TTL alone.
 */
class ClusterTimeWindowsTest {

    private static ClusterTimeWindows windows(Duration staleAfter, Duration leaseTtl) {
        ClusterProperties properties = new ClusterProperties();
        properties.setStaleAfter(staleAfter);
        properties.getLease().setTtl(leaseTtl);
        return new ClusterTimeWindows(properties);
    }

    @Test
    void routingAnswerMaxAge_isBoundedByPodLiveness_whenTheLeaseOutlivesIt() {
        // The shipped defaults: the lease is generous so a holder survives a GC
        // pause, liveness is tight so a dead address stops being dialled.
        ClusterTimeWindows w = windows(Duration.ofMinutes(2), Duration.ofMinutes(5));

        assertThat(w.routingAnswerMaxAge()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void routingAnswerMaxAge_isBoundedByTheLease_whenLivenessOutlivesIt() {
        ClusterTimeWindows w = windows(Duration.ofMinutes(10), Duration.ofMinutes(3));

        assertThat(w.routingAnswerMaxAge()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void defaults_keepTheLadderIntact() {
        ClusterTimeWindows w = new ClusterTimeWindows(new ClusterProperties());

        assertThat(w.heartbeatInterval()).isLessThan(w.podLiveness());
        assertThat(w.leaseRenewInterval()).isLessThan(w.leaseTtl());
        assertThat(w.podLiveness()).isLessThan(w.podRowRetention());
    }
}
