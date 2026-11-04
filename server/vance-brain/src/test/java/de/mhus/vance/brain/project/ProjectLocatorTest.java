package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacement;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Who asks decides what may be started. A machine trigger starts only a project that
 * wants to run; a user opening the project wakes it from any state but archived.
 */
class ProjectLocatorTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "work";

    private ProjectService projectService;
    private ClusterService clusterService;
    private ProjectPlacement placementService;
    private ProjectLocator locator;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        clusterService = mock(ClusterService.class);
        placementService = mock(ProjectPlacement.class);
        when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        locator = new ProjectLocator(projectService, clusterService, placementService);
    }

    private ProjectDocument given(ProjectStatus status) {
        ProjectDocument doc = ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(status)
                .lifecycleType(LifecycleType.AUTO)
                .build();
        when(projectService.findByTenantAndName(TENANT, PROJECT)).thenReturn(Optional.of(doc));
        when(projectService.transitionStatus(eq(TENANT), eq(PROJECT), any(), eq(ProjectStatus.RUNNING)))
                .thenAnswer(inv -> ProjectDocument.builder()
                        .tenantId(TENANT)
                        .name(PROJECT)
                        .status(ProjectStatus.RUNNING)
                        .lifecycleType(LifecycleType.AUTO)
                        .build());
        return doc;
    }

    @Test
    void aMachineTrigger_doesNotStartAnIdleProject() {
        given(ProjectStatus.IDLE);

        ProjectLocator.Location location = locator.locate(TENANT, PROJECT, true);

        assertThat(location.endpoint()).isEmpty();
        verify(placementService, never()).place(any(), any());
        verify(projectService, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    void aMachineTrigger_doesNotStartASuspendedProject() {
        given(ProjectStatus.SUSPENDED);

        assertThat(locator.locate(TENANT, PROJECT, true).endpoint()).isEmpty();

        verify(placementService, never()).place(any(), any());
    }

    @Test
    void aMachineTrigger_stillStartsAProjectThatWantsToRun() {
        given(ProjectStatus.RUNNING);

        locator.locate(TENANT, PROJECT, true);

        verify(placementService).place(any(ProjectDocument.class), eq(PlacementTrigger.LOCATE));
    }

    @Test
    void justLooking_startsNothing() {
        given(ProjectStatus.RUNNING);

        assertThat(locator.locate(TENANT, PROJECT, false).endpoint()).isEmpty();

        verify(placementService, never()).place(any(), any());
    }

    @Test
    void aUserOpeningAnIdleProject_wantsItRunningFirst_thenPlacesIt() {
        given(ProjectStatus.IDLE);

        locator.locateForUser(TENANT, PROJECT);

        InOrder order = inOrder(projectService, placementService);
        order.verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.IDLE, ProjectStatus.RUNNING);
        order.verify(placementService).place(any(ProjectDocument.class), eq(PlacementTrigger.WAKE));
    }

    @Test
    void aUserOpeningASuspendedProject_wakesItToo() {
        given(ProjectStatus.SUSPENDED);

        locator.locateForUser(TENANT, PROJECT);

        verify(projectService).transitionStatus(TENANT, PROJECT, ProjectStatus.SUSPENDED, ProjectStatus.RUNNING);
        verify(placementService).place(any(ProjectDocument.class), eq(PlacementTrigger.WAKE));
    }

    @Test
    void aUserOpeningARunningProject_writesNoDesiredState() {
        given(ProjectStatus.RUNNING);

        locator.locateForUser(TENANT, PROJECT);

        verify(projectService, never()).transitionStatus(any(), any(), any(), any());
        verify(placementService).place(any(ProjectDocument.class), eq(PlacementTrigger.WAKE));
    }

    private ProjectDocument doc(ProjectStatus status) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(status)
                .lifecycleType(LifecycleType.AUTO)
                .build();
    }

    @Test
    void aWakeThatLosesARaceToAnotherWake_readsAgainAndSucceeds() {
        given(ProjectStatus.IDLE);
        // First read: IDLE. The write loses; the re-read finds the project already woken.
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(doc(ProjectStatus.IDLE)))
                .thenReturn(Optional.of(doc(ProjectStatus.RUNNING)));
        when(projectService.transitionStatus(TENANT, PROJECT, ProjectStatus.IDLE, ProjectStatus.RUNNING))
                .thenThrow(new ProjectService.ProjectStatusConflictException("raced"));

        locator.locateForUser(TENANT, PROJECT);

        verify(placementService).place(any(ProjectDocument.class), eq(PlacementTrigger.WAKE));
    }

    @Test
    void aWakeThatLosesToAClose_isRefused() {
        given(ProjectStatus.IDLE);
        when(projectService.findByTenantAndName(TENANT, PROJECT))
                .thenReturn(Optional.of(doc(ProjectStatus.IDLE)))
                .thenReturn(Optional.of(doc(ProjectStatus.CLOSED)));
        when(projectService.transitionStatus(TENANT, PROJECT, ProjectStatus.IDLE, ProjectStatus.RUNNING))
                .thenThrow(new ProjectService.ProjectStatusConflictException("raced"));

        assertThatThrownBy(() -> locator.locateForUser(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectClosedException.class);

        verify(placementService, never()).place(any(), any());
    }

    @Test
    void aWakeThatKeepsLosing_givesUpAfterOneRetry() {
        given(ProjectStatus.IDLE);
        when(projectService.transitionStatus(TENANT, PROJECT, ProjectStatus.IDLE, ProjectStatus.RUNNING))
                .thenThrow(new ProjectService.ProjectStatusConflictException("raced"));

        assertThatThrownBy(() -> locator.locateForUser(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectStatusConflictException.class);

        verify(projectService, org.mockito.Mockito.times(2))
                .transitionStatus(TENANT, PROJECT, ProjectStatus.IDLE, ProjectStatus.RUNNING);
    }

    @Test
    void anArchivedProject_cannotBeOpened() {
        given(ProjectStatus.CLOSED);

        assertThatThrownBy(() -> locator.locateForUser(TENANT, PROJECT))
                .isInstanceOf(ProjectService.ProjectClosedException.class);

        verify(placementService, never()).place(any(), any());
    }

    @Test
    void aProjectThatAlreadyRunsSomewhere_isNotPlacedAgain() {
        ProjectDocument doc = given(ProjectStatus.RUNNING);
        doc.setHomePodId("pod-2");
        doc.setClaimedAt(Instant.now());
        when(clusterService.resolveEndpointByPodId("pod-2")).thenReturn(Optional.of("10.0.0.2:8080"));

        ProjectLocator.Location location = locator.locateForUser(TENANT, PROJECT);

        assertThat(location.endpoint()).contains("10.0.0.2:8080");
        verify(placementService, never()).place(any(), any());
    }
}
