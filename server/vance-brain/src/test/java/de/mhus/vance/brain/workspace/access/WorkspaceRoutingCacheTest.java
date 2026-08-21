package de.mhus.vance.brain.workspace.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceRoutingCache#isSelfOwned} — the self-ownership short-circuit
 * that lets the owning pod serve its workspace locally instead of proxying to
 * its own (often unreachable) advertised endpoint. Comparison is by node NAME.
 */
class WorkspaceRoutingCacheTest {

    private static final String SELF_NODE = "hari-tasha";
    private static final String SELF_POD = "pod-hari";

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
        lenient().when(clusterService.leaseTtl()).thenReturn(java.time.Duration.ofMinutes(5));
        cache = new WorkspaceRoutingCache(projectService, clusterService, properties);
    }

    /** A project with a freshly renewed lease held by {@code podId}. */
    private void projectWithLease(String tenant, String name, String podId) {
        when(projectService.findByTenantAndName(tenant, name))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId(tenant).name(name)
                        .homePodId(podId)
                        .homeNode(podId == null ? null : "node-of-" + podId)
                        .claimedAt(podId == null ? null : java.time.Instant.now())
                        .build()));
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
