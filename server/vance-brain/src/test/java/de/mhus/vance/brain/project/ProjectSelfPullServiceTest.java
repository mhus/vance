package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterProperties;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The self-pull's limits and its two switches. The pass is opportunistic, so
 * every guard here is "don't take anything" rather than "fail".
 */
class ProjectSelfPullServiceTest {

    private ProjectService projectService;
    private ProjectLifecycleService lifecycleService;
    private ClusterService clusterService;
    private ClusterProperties properties;
    private ProjectPlacementService placementService;
    private ProjectSelfPullService service;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        lifecycleService = mock(ProjectLifecycleService.class);
        clusterService = mock(ClusterService.class);
        placementService = mock(ProjectPlacementService.class);
        properties = new ClusterProperties();
        lenient().when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        lenient().when(clusterService.isRegistered()).thenReturn(true);
        lenient().when(placementService.localHeadroom()).thenReturn(1000);
        lenient().when(placementService.isEligibleHere(any(ProjectDocument.class)))
                .thenReturn(true);
        lenient().when(projectService.findProjectsNeedingOwner(any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        service = new ProjectSelfPullService(
                projectService, lifecycleService, clusterService, properties, placementService);
    }

    private static ProjectDocument candidate(String name, int score) {
        return ProjectDocument.builder()
                .tenantId("acme").name(name).homeResourceScore(score).build();
    }

    private void givenCandidates(ProjectDocument... docs) {
        when(projectService.findProjectsNeedingOwner(any(), anyInt(), anyInt()))
                .thenReturn(List.of(docs), List.of());
    }

    // ─── the scheduled switch ───────────────────────────────────────

    @Test
    void tick_scheduledOff_doesNothing() {
        // The tick fires regardless — the gate is in the body, same shape as
        // ClusterDistributorTick's master check.
        givenCandidates(candidate("p1", 1));

        service.tick();

        verify(projectService, never()).findProjectsNeedingOwner(any(), anyInt(), anyInt());
        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    @Test
    void tick_scheduledOn_pulls() {
        properties.getSelfPull().setScheduled(true);
        givenCandidates(candidate("p1", 1));

        service.tick();

        verify(lifecycleService).bring("acme", "p1");
    }

    @Test
    void tick_scheduledOn_survivesAFailingRound() {
        properties.getSelfPull().setScheduled(true);
        when(projectService.findProjectsNeedingOwner(any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("mongo hiccup"));

        service.tick();  // must not propagate — a scheduler thread dies on a throw
    }

    // ─── readyToPull ────────────────────────────────────────────────

    @Test
    void readyToPull_registersFirstAndAcceptsWhenRegistered() {
        assertThat(service.readyToPull()).isTrue();
        verify(clusterService).ensureRegistered();
    }

    @Test
    void readyToPull_refusesWhenTheRowIsMissing() {
        when(clusterService.isRegistered()).thenReturn(false);

        assertThat(service.readyToPull())
                .as("both limits default to permissive without the row — running "
                        + "unbounded is worse than not running")
                .isFalse();
    }

    // ─── the per-pass cap ───────────────────────────────────────────

    @Test
    void pullOnce_noBudget_takesNothing() {
        properties.getResources().setStartupScore(0);
        givenCandidates(candidate("p1", 1));

        service.pullOnce("test");

        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    @Test
    void pullOnce_noHeadroom_takesNothing() {
        when(placementService.localHeadroom()).thenReturn(0);
        givenCandidates(candidate("p1", 1));

        service.pullOnce("test");

        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    @Test
    void pullOnce_headroomBelowBudget_isTheBindingLimit() {
        // startupScore 100 (+50 buffer) would allow it; headroom says otherwise.
        // This is what makes the same budget safe on a repeated pass: as the pod
        // fills, headroom shrinks and the cap tightens with it.
        when(placementService.localHeadroom()).thenReturn(10);
        givenCandidates(candidate("big", 40));

        service.pullOnce("test");

        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    @Test
    void pullOnce_bufferLetsTheLastCandidateTipOver() {
        properties.getResources().setStartupScore(100);
        givenCandidates(candidate("big", 140));

        service.pullOnce("test");

        verify(lifecycleService).bring("acme", "big");
    }

    @Test
    void pullOnce_skipsIneligibleProjectsWithoutSpendingBudget() {
        ProjectDocument foreign = candidate("gpu-job", 10);
        ProjectDocument mine = candidate("mine", 10);
        when(placementService.isEligibleHere(foreign)).thenReturn(false);
        givenCandidates(foreign, mine);

        service.pullOnce("test");

        verify(lifecycleService, never()).bring("acme", "gpu-job");
        verify(lifecycleService).bring("acme", "mine");
    }

    @Test
    void pullOnce_claimRejected_isNotAnError() {
        when(lifecycleService.bring("acme", "p1"))
                .thenThrow(new ProjectManagerService.ClaimRejectedException("beaten to it"));
        givenCandidates(candidate("p1", 1));

        service.pullOnce("test");  // the CAS doing its job, not a failure
    }

    @Test
    void pullOnce_pagesPastAFullPageOfForeignProjects() {
        // The starvation this fixes: a full page of projects meant for other
        // pods must not read as "nothing left to do".
        ProjectDocument[] firstPage = new ProjectDocument[20];
        for (int i = 0; i < 20; i++) {
            firstPage[i] = candidate("foreign-" + i, 1);
            when(placementService.isEligibleHere(firstPage[i])).thenReturn(false);
        }
        when(projectService.findProjectsNeedingOwner(any(), anyInt(), anyInt()))
                .thenReturn(List.of(firstPage), List.of(candidate("mine", 1)), List.of());

        service.pullOnce("test");

        verify(lifecycleService).bring("acme", "mine");
    }
}
