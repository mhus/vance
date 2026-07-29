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

    private ProjectService projectService;
    private ClusterService clusterService;
    private WorkspaceRoutingCache cache;

    @BeforeEach
    void setup() {
        projectService = mock(ProjectService.class);
        clusterService = mock(ClusterService.class);
        WorkspaceAccessProperties properties = new WorkspaceAccessProperties();
        lenient().when(clusterService.selfNodeName()).thenReturn(SELF_NODE);
        cache = new WorkspaceRoutingCache(projectService, clusterService, properties);
    }

    private void projectWithHome(String tenant, String name, String homeNode) {
        when(projectService.findByTenantAndName(tenant, name))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId(tenant).name(name).homeNode(homeNode).build()));
    }

    @Test
    void isSelfOwned_homeNodeIsThisPod_true() {
        projectWithHome("acme", "test1", SELF_NODE);

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isTrue();
    }

    @Test
    void isSelfOwned_homeNodeIsForeignLivePod_false() {
        // A foreign owner must still be proxied — only self short-circuits.
        projectWithHome("acme", "test1", "pelican-mara");

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isFalse();
    }

    @Test
    void isSelfOwned_noHomeNode_false() {
        projectWithHome("acme", "test1", null);

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isFalse();
    }

    @Test
    void isSelfOwned_blankHomeNode_false() {
        projectWithHome("acme", "test1", "  ");

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "test1"))).isFalse();
    }

    @Test
    void isSelfOwned_projectMissing_false() {
        when(projectService.findByTenantAndName("acme", "ghost")).thenReturn(Optional.empty());

        assertThat(cache.isSelfOwned(new ProjectPodKey("acme", "ghost"))).isFalse();
    }
}
