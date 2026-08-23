package de.mhus.vance.brain.workspace.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceRoutingCache#isSelfOwned} — the self-ownership short-circuit
 * that lets the owning pod serve its workspace locally instead of proxying to
 * its own (often unreachable) advertised endpoint — plus the max-age semantics
 * of the cache itself.
 */
class WorkspaceRoutingCacheTest {

    private static final String SELF_NODE = "hari-tasha";
    private static final String SELF_POD = "pod-hari";
    private static final Duration ROUTING_WINDOW = Duration.ofMinutes(2);

    private ProjectService projectService;
    private ClusterService clusterService;
    private WorkspaceRoutingCache cache;

    @BeforeEach
    void setup() {
        projectService = mock(ProjectService.class);
        clusterService = mock(ClusterService.class);
        WorkspaceAccessProperties properties = new WorkspaceAccessProperties();
        lenient().when(clusterService.selfNodeName()).thenReturn(SELF_NODE);
        lenient().when(clusterService.selfPodId()).thenReturn(SELF_POD);
        lenient().when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        lenient().when(clusterService.routingAnswerMaxAge()).thenReturn(ROUTING_WINDOW);
        cache = new WorkspaceRoutingCache(projectService, clusterService, properties);
    }

    /** A project with a freshly renewed lease held by {@code podId}. */
    private void projectWithLease(String tenant, String name, String podId) {
        when(projectService.findByTenantAndName(tenant, name))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId(tenant).name(name)
                        .homePodId(podId)
                        .homeNode(podId == null ? null : "node-of-" + podId)
                        .claimedAt(podId == null ? null : Instant.now())
                        .build()));
    }

    // ─── max-age, not idle timeout ───────────────────────────────────

    @Test
    void lookup_hotRoute_revalidatesOnceTheRoutingWindowPassed() {
        // The regression: expiry used to be measured from "last used" and
        // every hit pushed that forward, so a route asked for more often than
        // the TTL never went back to Mongo — and the failure it has to catch
        // (holder lost the lease but is still up and answering) produces no
        // connect error to invalidate on.
        ProjectPodKey key = new ProjectPodKey("acme", "hot");
        projectWithLease("acme", "hot", "pod-pelican");
        when(clusterService.resolveEndpointByPodId("pod-pelican"))
                .thenReturn(Optional.of("10.0.0.4:8080"));
        Instant t0 = Instant.parse("2026-08-23T10:00:00Z");

        assertThat(cache.lookup(key, t0)).contains("10.0.0.4:8080");
        // Kept warm well inside the window — served from the entry.
        assertThat(cache.lookup(key, t0.plusSeconds(60))).contains("10.0.0.4:8080");
        assertThat(cache.lookup(key, t0.plusSeconds(110))).contains("10.0.0.4:8080");
        verify(projectService, times(1)).findByTenantAndName("acme", "hot");

        // Past the window the answer has to be re-derived, hot or not.
        assertThat(cache.lookup(key, t0.plus(ROUTING_WINDOW).plusSeconds(1)))
                .contains("10.0.0.4:8080");
        verify(projectService, times(2)).findByTenantAndName("acme", "hot");
    }

    @Test
    void lookup_maxAgeNeverExceedsTheClusterRoutingWindow() {
        // The local knob may shorten the window, never lengthen it: the
        // default cache-ttl is 30 minutes and must not survive the 2-minute
        // routing window it was derived behind.
        ProjectPodKey key = new ProjectPodKey("acme", "hot");
        projectWithLease("acme", "hot", "pod-pelican");
        when(clusterService.resolveEndpointByPodId("pod-pelican"))
                .thenReturn(Optional.of("10.0.0.4:8080"));
        Instant t0 = Instant.parse("2026-08-23T10:00:00Z");

        cache.lookup(key, t0);
        cache.lookup(key, t0.plus(Duration.ofMinutes(3)));

        verify(projectService, times(2)).findByTenantAndName("acme", "hot");
    }

    @Test
    void engineStopEvent_dropsTheCachedRoute() {
        ProjectPodKey key = new ProjectPodKey("acme", "lost");
        projectWithLease("acme", "lost", "pod-pelican");
        when(clusterService.resolveEndpointByPodId("pod-pelican"))
                .thenReturn(Optional.of("10.0.0.4:8080"));
        Instant t0 = Instant.parse("2026-08-23T10:00:00Z");
        cache.lookup(key, t0);

        cache.onProjectEnginesStopRequested(
                new ProjectEnginesStopRequested("acme", "lost"));

        cache.lookup(key, t0.plusSeconds(1));
        verify(projectService, times(2)).findByTenantAndName("acme", "lost");
    }

    @Test
    void isSelfOwned_leaseHeldByThisPod_true() {
        projectWithLease("acme", "test1", SELF_POD);

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isTrue();
    }

    @Test
    void isSelfOwned_leaseHeldByForeignPod_false() {
        // A foreign owner must still be proxied — only self short-circuits.
        projectWithLease("acme", "test1", "pod-pelican");

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isFalse();
    }

    @Test
    void isSelfOwned_noLease_false() {
        projectWithLease("acme", "test1", null);

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isFalse();
    }

    @Test
    void isSelfOwned_blankHomeNode_false() {
        projectWithLease("acme", "test1", "  ");

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isFalse();
    }

    @Test
    void isSelfOwned_projectMissing_false() {
        when(projectService.findByTenantAndName("acme", "ghost")).thenReturn(Optional.empty());

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "ghost"))).isFalse();
    }
}
