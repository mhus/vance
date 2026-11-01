package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A clean shutdown takes the projects down in order — engines unloaded, then
 * leases dropped (which also records the actual state as STOPPED) — and never
 * rewrites the desired state: a restart is not a decision about the project.
 */
class ProjectLeaseShutdownTest {

    @Test
    void shutdown_unloadsActivatedProjects_thenReleasesTheLeases_andWritesNoDesiredState() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.selfPodId()).thenReturn("pod-1");
        when(clusterService.selfNodeName()).thenReturn("node-1");
        when(clusterService.selfEndpoint()).thenReturn("host:1");
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ProjectActivationRegistry registry = new ProjectActivationRegistry();
        registry.activate("acme", "alpha");
        registry.activate("acme", "beta");

        ProjectLeaseService service =
                new ProjectLeaseService(projectService, clusterService, registry, events, mock(MegadodoService.class));

        service.releaseOnShutdown();

        InOrder order = inOrder(events, projectService);
        order.verify(events, org.mockito.Mockito.times(2)).publishEvent(any(ProjectEnginesStopRequested.class));
        order.verify(projectService).releaseLeases("pod-1", "node-1", "host:1");
        assertThat(registry.size()).isZero();
        verify(projectService, never()).transitionStatus(anyString(), anyString(), any(), any());
    }

    @Test
    void shutdown_aListenerThatThrows_doesNotKeepTheLeasesFromBeingReleased() {
        ProjectService projectService = mock(ProjectService.class);
        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.selfPodId()).thenReturn("pod-1");
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("listener gone"))
                .when(events)
                .publishEvent(any(ProjectEnginesStopRequested.class));
        ProjectActivationRegistry registry = new ProjectActivationRegistry();
        registry.activate("acme", "alpha");

        ProjectLeaseService service =
                new ProjectLeaseService(projectService, clusterService, registry, events, mock(MegadodoService.class));

        service.releaseOnShutdown();

        verify(projectService).releaseLeases(any(), any(), any());
    }
}
