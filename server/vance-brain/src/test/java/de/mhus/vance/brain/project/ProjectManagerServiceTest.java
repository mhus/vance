package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProjectManagerService#findProjectEndpoint} must not route to a pod
 * that stopped renewing its lease — otherwise a session whose holder crashed
 * (or moved host IP) stays permanently unreachable, every resume tunnelled to
 * a dead endpoint (observed 2026-07-01).
 *
 * <p>The liveness question is now answered by the lease on the document itself
 * ({@code ProjectOwnership}, unit-tested separately), so what we assert here is
 * that {@code findProjectEndpoint} goes through it and never resolves an
 * endpoint for an expired lease.
 */
class ProjectManagerServiceTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String SELF_POD = "pod-self";

    private final ProjectService projectService = mock(ProjectService.class);
    private final ClusterService clusterService = mock(ClusterService.class);
    private final ProjectManagerService manager = new ProjectManagerService(
            projectService, clusterService, null, null, null);

    @BeforeEach
    void stubCluster() {
        when(clusterService.leaseTtl()).thenReturn(TTL);
        when(clusterService.selfPodId()).thenReturn(SELF_POD);
    }

    private void givenLease(String podId, Instant claimedAt) {
        when(projectService.findByTenantAndName("acme", "test1"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId("acme").name("test1")
                        .homePodId(podId).homeNode("naga-vorlon")
                        .claimedAt(claimedAt)
                        .build()));
    }

    @Test
    void validLease_resolvesHolderEndpoint() {
        givenLease("pod-holder", Instant.now());
        when(clusterService.resolveEndpointByPodId("pod-holder"))
                .thenReturn(Optional.of("192.168.1.113:9991"));

        assertThat(manager.findProjectEndpoint("acme", "test1")).contains("192.168.1.113:9991");
    }

    @Test
    void expiredLease_returnsEmptySoTheWsFallsBackToLocal() {
        givenLease("pod-holder", Instant.now().minus(TTL).minusSeconds(1));

        // No endpoint stub on purpose: an expired lease must be rejected
        // before an endpoint is ever looked up. That empty is what lets the
        // WS-receiving pod serve the session locally.
        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void leaseWithoutRenewalTimestamp_returnsEmpty() {
        givenLease("pod-holder", null);

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void holderRowGone_returnsEmpty() {
        givenLease("pod-holder", Instant.now());
        when(clusterService.resolveEndpointByPodId("pod-holder")).thenReturn(Optional.empty());

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void unclaimedProject_returnsEmpty() {
        givenLease(null, null);

        assertThat(manager.findProjectEndpoint("acme", "test1")).isEmpty();
    }

    @Test
    void podlessProject_returnsEmptyWithoutTouchingTheLease() {
        assertThat(manager.findProjectEndpoint("acme", "_user_marvin")).isEmpty();
    }
}
