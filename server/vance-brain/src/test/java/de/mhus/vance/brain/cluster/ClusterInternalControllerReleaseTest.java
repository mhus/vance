package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

/**
 * A hand-over that exists only to move a project (a rebalance) must not strand it: a workspace
 * never moves between pods, so the holder refuses while it keeps one.
 */
class ClusterInternalControllerReleaseTest {

    private ProjectLifecycleService lifecycle;
    private ClusterInternalController controller;

    @BeforeEach
    void setUp() {
        lifecycle = mock(ProjectLifecycleService.class);
        controller = new ClusterInternalController(
                lifecycle,
                mock(BrainPodService.class),
                mock(ProjectService.class),
                mock(ClusterService.class),
                mock(ProjectPlacementService.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void aPlainRelease_ignoresTheWorkspace() {
        when(lifecycle.release("acme", "p", false)).thenReturn(true);

        ResponseEntity<?> response = controller.release(new HttpClusterBringClient.ReleaseRequest("acme", "p", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(lifecycle, never()).holdsWorkspace(anyString(), anyString());
    }

    @Test
    void requireNoWorkspace_refusesWhileThePodKeepsOne() {
        when(lifecycle.holdsWorkspace("acme", "p")).thenReturn(true);

        ResponseEntity<?> response =
                controller.release(new HttpClusterBringClient.ReleaseRequest("acme", "p", null, true));

        assertThat(response.getStatusCode().value()).isEqualTo(412);
        verify(lifecycle, never()).release(anyString(), anyString(), anyBoolean());
    }

    @Test
    void requireNoWorkspace_releasesWhenThereIsNone() {
        when(lifecycle.holdsWorkspace("acme", "p")).thenReturn(false);
        when(lifecycle.release("acme", "p", false)).thenReturn(true);

        ResponseEntity<?> response =
                controller.release(new HttpClusterBringClient.ReleaseRequest("acme", "p", null, true));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void notHeldHere_isStillA409() {
        when(lifecycle.holdsWorkspace("acme", "p")).thenReturn(false);
        when(lifecycle.release("acme", "p", false)).thenReturn(false);

        ResponseEntity<?> response =
                controller.release(new HttpClusterBringClient.ReleaseRequest("acme", "p", null, true));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }
}
