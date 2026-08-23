package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Losing a lease while still running is the case nothing used to notice: the
 * pod kept scheduler, hooks and tool scopes loaded for a project another pod
 * had legitimately taken over. These tests pin the detection (counts disagree)
 * and the teardown (stop event, no workspace write).
 */
class ProjectLeaseServiceTest {

    private static final String SELF_POD = "pod-self";

    private ProjectService projectService;
    private ClusterService clusterService;
    private ProjectActivationRegistry registry;
    private ApplicationEventPublisher eventPublisher;
    private ProjectLeaseService service;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        clusterService = mock(ClusterService.class);
        registry = new ProjectActivationRegistry();
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(clusterService.selfPodId()).thenReturn(SELF_POD);
        service = new ProjectLeaseService(
                projectService, clusterService, registry, eventPublisher);
    }

    private static ProjectDocument project(String name) {
        return ProjectDocument.builder().tenantId("acme").name(name).build();
    }

    @Test
    void allLeasesRenewed_doesNotQueryProjectsAgain() {
        registry.activate("acme", "test1");
        registry.activate("acme", "test2");
        when(projectService.renewLeases(eq(SELF_POD), any(Instant.class))).thenReturn(2L);

        service.renewAndReconcile();

        // The normal path must cost exactly one write — no per-project read.
        verify(projectService, never()).findByHomePodId(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void lostLease_deactivatesLocallyAndRequestsEngineStop() {
        registry.activate("acme", "test1");
        registry.activate("acme", "test2");
        when(projectService.renewLeases(eq(SELF_POD), any(Instant.class))).thenReturn(1L);
        when(projectService.findByHomePodId(SELF_POD)).thenReturn(List.of(project("test1")));

        service.renewAndReconcile();

        assertThat(registry.isActive("acme", "test1")).isTrue();
        assertThat(registry.isActive("acme", "test2")).isFalse();
        verify(eventPublisher, times(1))
                .publishEvent(new ProjectEnginesStopRequested("acme", "test2"));
    }

    @Test
    void lostLease_doesNotTouchTheWorkspace() {
        // Nothing here can write: the service has no WorkspaceService at all,
        // which is the point — the new owner already initialised the workspace
        // from Mongo, and snapshotting our stale copy back would overwrite it.
        registry.activate("acme", "test1");
        when(projectService.renewLeases(eq(SELF_POD), any(Instant.class))).thenReturn(0L);
        when(projectService.findByHomePodId(SELF_POD)).thenReturn(List.of());

        service.renewAndReconcile();

        assertThat(registry.size()).isZero();
    }

    @Test
    void podlessProjects_areNotCountedAsLost() {
        // Podless projects never take a lease, so they can never be renewed —
        // treating them as lost would tear down Eddie's local state on every
        // beat.
        registry.activate("acme", "_user_marvin");
        when(projectService.renewLeases(eq(SELF_POD), any(Instant.class))).thenReturn(0L);
        when(projectService.findByHomePodId(SELF_POD)).thenReturn(List.of());

        service.renewAndReconcile();

        assertThat(registry.isActive("acme", "_user_marvin")).isTrue();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void podlessProjects_doNotTriggerReconciliationAtAll() {
        // Not just "survives reconciliation" — must not reach it. One connected
        // user used to make the counts disagree forever, so every beat logged
        // "a lease was taken away" and paid for the query behind it, which then
        // skipped exactly the project that had raised the alarm.
        registry.activate("acme", "_user_marvin");
        registry.activate("acme", "test1");
        when(projectService.renewLeases(eq(SELF_POD), any(Instant.class))).thenReturn(1L);

        service.renewAndReconcile();

        verify(projectService, never()).findByHomePodId(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shutdown_releasesLeases() {
        when(projectService.releaseLeases(SELF_POD)).thenReturn(3L);

        service.releaseOnShutdown();

        verify(projectService, times(1)).releaseLeases(SELF_POD);
    }

    @Test
    void shutdown_swallowsFailureBecauseLeasesExpireAnyway() {
        when(projectService.releaseLeases(SELF_POD))
                .thenThrow(new IllegalStateException("mongo gone"));

        service.releaseOnShutdown();
    }
}
