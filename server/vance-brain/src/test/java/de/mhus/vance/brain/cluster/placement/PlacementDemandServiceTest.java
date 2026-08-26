package de.mhus.vance.brain.cluster.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterProperties;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregation of unmet placement demand: grouping, the gap classification, and
 * the union of the two candidate sources.
 */
class PlacementDemandServiceTest {

    private ProjectService projectService;
    private ClusterService clusterService;
    private ProjectPlacementService placementService;
    private PlacementDemandService service;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        clusterService = mock(ClusterService.class);
        placementService = mock(ProjectPlacementService.class);
        ClusterProperties properties = new ClusterProperties();
        lenient().when(clusterService.selfClusterId()).thenReturn("default");
        lenient().when(clusterService.leaseTtl()).thenReturn(Duration.ofMinutes(5));
        lenient().when(projectService.findProjectsNeedingOwner(any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(projectService.findPendingPlacement(any(), any())).thenReturn(List.of());
        service = new PlacementDemandService(
                projectService, clusterService, properties, placementService);
    }

    private static ProjectDocument waiting(
            String tenant, String name, int score, Map<String, String> selector, Instant since) {
        return ProjectDocument.builder()
                .tenantId(tenant).name(name).homeResourceScore(score)
                .placementSelector(new HashMap<>(selector))
                .pendingSince(since)
                .build();
    }

    private void unschedulable(ProjectDocument project, PlacementGap gap) {
        when(placementService.evaluate(project))
                .thenReturn(new PlacementDecision.Unschedulable(gap));
    }

    @Test
    void groupsByTenantAndSelector_summingScoreAndKeepingTheOldestWait() {
        Instant older = Instant.parse("2026-08-26T09:00:00Z");
        Instant newer = Instant.parse("2026-08-26T09:30:00Z");
        ProjectDocument a = waiting("acme", "a", 20, Map.of("gpu", "true"), newer);
        ProjectDocument b = waiting("acme", "b", 20, Map.of("gpu", "true"), older);
        when(projectService.findProjectsNeedingOwner(any(), anyInt())).thenReturn(List.of(a, b));
        unschedulable(a, PlacementGap.NO_ELIGIBLE_POD);
        unschedulable(b, PlacementGap.NO_ELIGIBLE_POD);

        PlacementDemand demand = service.currentDemand();

        assertThat(demand.demand()).hasSize(1);
        PlacementDemand.Entry entry = demand.demand().get(0);
        assertThat(entry.projectCount()).isEqualTo(2);
        assertThat(entry.requiredScore())
                .as("the order is 'this much score needs a home', not 'two projects'")
                .isEqualTo(40);
        assertThat(entry.oldestSince()).isEqualTo(older);
        assertThat(entry.selector()).containsExactlyEntriesOf(Map.of("gpu", "true"));
    }

    @Test
    void differentSelectors_areDifferentOrders() {
        ProjectDocument gpu = waiting("acme", "gpu", 1, Map.of("gpu", "true"), Instant.now());
        ProjectDocument eu = waiting("acme", "eu", 1, Map.of("region", "eu"), Instant.now());
        when(projectService.findProjectsNeedingOwner(any(), anyInt())).thenReturn(List.of(gpu, eu));
        unschedulable(gpu, PlacementGap.NO_ELIGIBLE_POD);
        unschedulable(eu, PlacementGap.NO_ELIGIBLE_POD);

        assertThat(service.currentDemand().demand()).hasSize(2);
    }

    @Test
    void differentTenants_areDifferentOrders_evenWithTheSameSelector() {
        ProjectDocument one = waiting("acme", "a", 1, Map.of("gpu", "true"), Instant.now());
        ProjectDocument two = waiting("globex", "a", 1, Map.of("gpu", "true"), Instant.now());
        when(projectService.findProjectsNeedingOwner(any(), anyInt())).thenReturn(List.of(one, two));
        unschedulable(one, PlacementGap.NO_ELIGIBLE_POD);
        unschedulable(two, PlacementGap.NO_ELIGIBLE_POD);

        assertThat(service.currentDemand().demand()).hasSize(2);
    }

    @Test
    void tenantFilter_narrowsToOneSlice() {
        ProjectDocument one = waiting("acme", "a", 1, Map.of(), Instant.now());
        ProjectDocument two = waiting("globex", "a", 1, Map.of(), Instant.now());
        when(projectService.findProjectsNeedingOwner(any(), anyInt())).thenReturn(List.of(one, two));
        unschedulable(one, PlacementGap.NO_CAPACITY);
        unschedulable(two, PlacementGap.NO_CAPACITY);

        PlacementDemand demand = service.currentDemand("acme");

        assertThat(demand.demand()).hasSize(1);
        assertThat(demand.demand().get(0).tenantId()).isEqualTo("acme");
    }

    @Test
    void placeableProject_isNotDemand_evenWithAStalePendingMark() {
        ProjectDocument stale = waiting("acme", "a", 1, Map.of(), Instant.now());
        when(projectService.findPendingPlacement(any(), any())).thenReturn(List.of(stale));
        when(placementService.evaluate(stale)).thenReturn(new PlacementDecision.Here());

        assertThat(service.currentDemand().demand())
                .as("a mark left over from an earlier attempt is not a need")
                .isEmpty();
    }

    @Test
    void pendingSetContributesProjectsTheOrphanQueryDoesNotSee() {
        // The case a person is waiting on: a fresh project with an unsatisfiable
        // selector is in neither ownerRequired nor PERMANENT, so only the
        // pendingSince set can report it.
        ProjectDocument fresh = waiting("acme", "fresh", 5, Map.of("gpu", "true"), Instant.now());
        when(projectService.findProjectsNeedingOwner(any(), anyInt())).thenReturn(List.of());
        when(projectService.findPendingPlacement(any(), any())).thenReturn(List.of(fresh));
        unschedulable(fresh, PlacementGap.NO_ELIGIBLE_POD);

        assertThat(service.currentDemand().demand()).hasSize(1);
    }

    @Test
    void aProjectInBothSources_isCountedOnce() {
        ProjectDocument both = waiting("acme", "a", 7, Map.of("gpu", "true"), Instant.now());
        when(projectService.findProjectsNeedingOwner(any(), anyInt())).thenReturn(List.of(both));
        when(projectService.findPendingPlacement(any(), any())).thenReturn(List.of(both));
        unschedulable(both, PlacementGap.NO_ELIGIBLE_POD);

        PlacementDemand demand = service.currentDemand();

        assertThat(demand.demand()).hasSize(1);
        assertThat(demand.demand().get(0).projectCount()).isEqualTo(1);
        assertThat(demand.demand().get(0).requiredScore()).isEqualTo(7);
    }

    @Test
    void projectWithoutAMarkYet_countsAsWaitingSinceNow() {
        ProjectDocument unmarked = waiting("acme", "a", 1, Map.of(), null);
        when(projectService.findProjectsNeedingOwner(any(), anyInt()))
                .thenReturn(List.of(unmarked));
        unschedulable(unmarked, PlacementGap.NO_CAPACITY);

        assertThat(service.currentDemand().demand().get(0).oldestSince())
                .as("a null timestamp would force every consumer to invent a fallback")
                .isNotNull();
    }

    @Test
    void entriesAreSortedLongestWaitingFirst() {
        Instant old = Instant.parse("2026-08-26T08:00:00Z");
        Instant recent = Instant.parse("2026-08-26T10:00:00Z");
        ProjectDocument young = waiting("acme", "young", 1, Map.of("a", "1"), recent);
        ProjectDocument aged = waiting("acme", "aged", 1, Map.of("b", "2"), old);
        when(projectService.findProjectsNeedingOwner(any(), anyInt()))
                .thenReturn(List.of(young, aged));
        unschedulable(young, PlacementGap.NO_ELIGIBLE_POD);
        unschedulable(aged, PlacementGap.NO_ELIGIBLE_POD);

        assertThat(service.currentDemand().demand())
                .extracting(PlacementDemand.Entry::oldestSince)
                .containsExactly(old, recent);
    }
}
