package de.mhus.vance.brain.cluster.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Filter/fit decisions and dispatch for the placement façade — the behaviour
 * that used to be spread over seven call sites with two divergent copies of
 * the pick loop ({@code planning/project-placement-labels.md} §1).
 */
class ProjectPlacementServiceTest {

    private static final String SELF_NODE = "self-node";

    private ClusterService clusterService;
    private ProjectService projectService;
    private ClusterBringClient bringClient;
    private ProjectLifecycleService lifecycleService;
    private ProjectPlacementService placement;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clusterService = mock(ClusterService.class);
        projectService = mock(ProjectService.class);
        bringClient = mock(ClusterBringClient.class);
        lifecycleService = mock(ProjectLifecycleService.class);
        ObjectProvider<ProjectLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        lenient().when(lifecycleProvider.getObject()).thenReturn(lifecycleService);
        lenient().when(clusterService.selfNodeName()).thenReturn(SELF_NODE);
        lenient().when(clusterService.selfEndpoint()).thenReturn("self:9990");
        lenient().when(clusterService.selfPod()).thenReturn(Optional.empty());
        lenient().when(clusterService.liveClusterPods()).thenReturn(List.of());
        placement = new ProjectPlacementService(
                clusterService, projectService, bringClient, lifecycleProvider);
    }

    private static ProjectDocument project(String name, int score) {
        return ProjectDocument.builder()
                .tenantId("acme").name(name).homeResourceScore(score)
                .lifecycleType(LifecycleType.AUTO)
                .build();
    }

    private static BrainPodDocument pod(String node, int current, int max) {
        return BrainPodDocument.builder()
                .nodeName(node).endpoint(node + ":9990")
                .resourcesCurrentScore(current).resourcesMaxScore(max)
                .build();
    }

    private void givenSelfPod(int current, int max) {
        when(clusterService.selfPod()).thenReturn(Optional.of(pod(SELF_NODE, current, max)));
    }

    // ─── filter: projects without pod affinity ──────────────────────

    @Test
    void decide_homelessProject_staysHereWithoutLookingAtTheCluster() {
        ProjectDocument homeless = ProjectDocument.builder()
                .tenantId("acme").name("regular-name")
                .lifecycleType(LifecycleType.HOMELESS).homeResourceScore(1)
                .build();

        assertThat(placement.decide(homeless, PlacementTrigger.DISTRIBUTOR))
                .isInstanceOf(PlacementDecision.Here.class);
        verify(clusterService, never()).liveClusterPods();
    }

    @Test
    void decide_podlessNameWithAutoLifecycle_staysHere() {
        // Legacy documents exist where kind/lifecycleType disagree with the
        // name; routing such a project away would send it where it can never
        // be owned.
        ProjectDocument legacy = ProjectDocument.builder()
                .tenantId("acme").name("_user_wile.coyote")
                .lifecycleType(LifecycleType.AUTO).homeResourceScore(1)
                .build();

        assertThat(placement.decide(legacy, PlacementTrigger.DISTRIBUTOR))
                .isInstanceOf(PlacementDecision.Here.class);
    }

    // ─── local preference is the trigger's call ─────────────────────

    @Test
    void decide_localPreferringTrigger_withRoom_staysHere() {
        givenSelfPod(10, 100);
        when(clusterService.liveClusterPods())
                .thenReturn(List.of(pod("cold-other", 0, 100), pod(SELF_NODE, 10, 100)));

        assertThat(placement.decide(project("p1", 5), PlacementTrigger.CREATE))
                .as("a project that fits here must not pay a network hop")
                .isInstanceOf(PlacementDecision.Here.class);
    }

    @Test
    void decide_distributorTrigger_ignoresLocalPreferenceAndPicksLeastLoaded() {
        givenSelfPod(10, 100);
        when(clusterService.liveClusterPods())
                .thenReturn(List.of(pod("cold-other", 0, 100), pod(SELF_NODE, 10, 100)));

        PlacementDecision decision = placement.decide(project("p1", 5), PlacementTrigger.DISTRIBUTOR);

        assertThat(decision).isInstanceOf(PlacementDecision.On.class);
        assertThat(((PlacementDecision.On) decision).pod().getNodeName()).isEqualTo("cold-other");
    }

    @Test
    void decide_localPreferringTrigger_withoutRoom_routesToAnotherPodInsteadOfOverbooking() {
        givenSelfPod(98, 100);
        when(clusterService.liveClusterPods())
                .thenReturn(List.of(pod("roomy", 0, 100), pod(SELF_NODE, 98, 100)));

        PlacementDecision decision = placement.decide(project("p1", 10), PlacementTrigger.CREATE);

        assertThat(decision).isInstanceOf(PlacementDecision.On.class);
        assertThat(((PlacementDecision.On) decision).pod().getNodeName()).isEqualTo("roomy");
    }

    @Test
    void decide_unregisteredPod_staysHereBecauseUnknownIsNotFull() {
        // selfPod() is empty until registration completes. The boot path must
        // still be able to place, exactly as the two deleted haveLocalRoom()
        // copies both defaulted to "there is room".
        assertThat(placement.decide(project("p1", 5000), PlacementTrigger.CREATE))
                .isInstanceOf(PlacementDecision.Here.class);
    }

    // ─── fit ────────────────────────────────────────────────────────

    @Test
    void decide_noLivePods_reportsNoEligiblePod() {
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of());

        PlacementDecision decision = placement.decide(project("p1", 1), PlacementTrigger.CREATE);

        assertThat(decision).isEqualTo(
                new PlacementDecision.Unschedulable(PlacementGap.NO_ELIGIBLE_POD));
    }

    @Test
    void decide_podsButNoneWithRoom_reportsNoCapacity() {
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of(pod("full", 9999, 10000)));

        PlacementDecision decision = placement.decide(project("big", 500), PlacementTrigger.CREATE);

        assertThat(decision).as("eligible pods exist — this is a scale-out, not a new pod kind")
                .isEqualTo(new PlacementDecision.Unschedulable(PlacementGap.NO_CAPACITY));
    }

    @Test
    void decide_skipsLighterLoadedPodThatCannotFit() {
        givenSelfPod(100, 100);
        // 'tight' is lighter-loaded but has only 5 units left.
        when(clusterService.liveClusterPods())
                .thenReturn(List.of(pod("tight", 95, 100), pod("roomy", 50, 100)));

        PlacementDecision decision = placement.decide(project("p1", 10), PlacementTrigger.DISTRIBUTOR);

        assertThat(((PlacementDecision.On) decision).pod().getNodeName()).isEqualTo("roomy");
    }

    @Test
    void decide_podWithUnsetMaxScore_isTreatedAsCapacityOne() {
        // The divergence between the two former copies: one clamped maxScore to
        // at least 1, the other compared against a raw 0 and never fit anything.
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of(pod("legacy-row", 0, 0)));

        PlacementDecision decision = placement.decide(project("p1", 1), PlacementTrigger.DISTRIBUTOR);

        assertThat(((PlacementDecision.On) decision).pod().getNodeName()).isEqualTo("legacy-row");
    }

    // ─── batch: the reservation buffer ──────────────────────────────

    @Test
    void decideBatch_spreadsAcrossPodsInsteadOfOverbookingTheCheapest() {
        when(clusterService.liveClusterPods())
                .thenReturn(List.of(pod("a", 0, 10), pod("b", 0, 10)));

        List<PlacementDecision> decisions = placement.decideBatch(
                List.of(project("p1", 10), project("p2", 10)));

        assertThat(decisions).hasSize(2);
        assertThat(((PlacementDecision.On) decisions.get(0)).pod().getNodeName()).isEqualTo("a");
        assertThat(((PlacementDecision.On) decisions.get(1)).pod().getNodeName())
                .as("p1 already filled 'a' for this round")
                .isEqualTo("b");
    }

    @Test
    void decideBatch_runsOutOfRoomMidRound_reportsNoCapacityForTheRest() {
        when(clusterService.liveClusterPods()).thenReturn(List.of(pod("a", 0, 10)));

        List<PlacementDecision> decisions = placement.decideBatch(
                List.of(project("p1", 10), project("p2", 1)));

        assertThat(((PlacementDecision.On) decisions.get(0)).pod().getNodeName()).isEqualTo("a");
        assertThat(decisions.get(1)).isEqualTo(
                new PlacementDecision.Unschedulable(PlacementGap.NO_CAPACITY));
    }

    @Test
    void decideBatch_neverPrefersLocal() {
        givenSelfPod(0, 100);
        when(clusterService.liveClusterPods())
                .thenReturn(List.of(pod("cold-other", 0, 100), pod(SELF_NODE, 0, 100)));

        List<PlacementDecision> decisions = placement.decideBatch(List.of(project("p1", 1)));

        assertThat(((PlacementDecision.On) decisions.get(0)).pod().getNodeName())
                .as("distributing must not pile the round onto the deciding pod")
                .isEqualTo("cold-other");
    }

    // ─── dispatch ───────────────────────────────────────────────────

    @Test
    void dispatch_here_bringsLocally() {
        placement.dispatch(new PlacementDecision.Here(), project("p1", 1));

        verify(lifecycleService).bring("acme", "p1");
        verify(bringClient, never()).requestBring(anyString(), anyString(), anyString());
    }

    @Test
    void dispatch_onSelf_bringsLocallyRatherThanCallingItself() {
        placement.dispatch(new PlacementDecision.On(pod(SELF_NODE, 0, 100)), project("p1", 1));

        verify(lifecycleService).bring("acme", "p1");
        verify(bringClient, never()).requestBring(anyString(), anyString(), anyString());
    }

    @Test
    void dispatch_onRemote_callsBringClient() {
        placement.dispatch(new PlacementDecision.On(pod("other", 0, 100)), project("p1", 1));

        verify(bringClient).requestBring("other:9990", "acme", "p1");
        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    @Test
    void dispatch_unschedulable_isAProgrammingError() {
        assertThatThrownBy(() -> placement.dispatch(
                new PlacementDecision.Unschedulable(PlacementGap.NO_CAPACITY), project("p1", 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void place_unschedulable_throwsClusterFullCarryingTheGap() {
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of());

        assertThatThrownBy(() -> placement.place(project("p1", 1), PlacementTrigger.CREATE))
                .isInstanceOf(ClusterFullException.class)
                .extracting(e -> ((ClusterFullException) e).getGap())
                .isEqualTo(PlacementGap.NO_ELIGIBLE_POD);
        verify(lifecycleService, never()).bring(anyString(), anyString());
    }

    // ─── filter: labels and selectors ───────────────────────────────

    private static ProjectDocument requiring(String name, int score, Map<String, String> selector) {
        return ProjectDocument.builder()
                .tenantId("acme").name(name).homeResourceScore(score)
                .lifecycleType(LifecycleType.AUTO)
                .placementSelector(new HashMap<>(selector))
                .build();
    }

    private static BrainPodDocument labelled(
            String node, int current, int max, Map<String, String> labels, boolean exclusive) {
        return BrainPodDocument.builder()
                .nodeName(node).endpoint(node + ":9990")
                .resourcesCurrentScore(current).resourcesMaxScore(max)
                .labels(new HashMap<>(labels)).exclusive(exclusive)
                .build();
    }

    @Test
    void decide_skipsEligibleButWrongLabelledPod_evenWhenItIsTheCheapest() {
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                labelled("plain", 0, 100, Map.of(), false),
                labelled("gpu-pod", 50, 100, Map.of("gpu", "true"), false)));

        PlacementDecision decision = placement.decide(
                requiring("trainer", 5, Map.of("gpu", "true")), PlacementTrigger.DISTRIBUTOR);

        assertThat(((PlacementDecision.On) decision).pod().getNodeName()).isEqualTo("gpu-pod");
    }

    @Test
    void decide_noPodCarriesTheLabels_reportsNoEligiblePodNotNoCapacity() {
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                labelled("plain", 0, 10000, Map.of(), false)));

        PlacementDecision decision = placement.decide(
                requiring("trainer", 1, Map.of("gpu", "true")), PlacementTrigger.CREATE);

        assertThat(decision).as("there is plenty of room — the missing thing is a kind of pod")
                .isEqualTo(new PlacementDecision.Unschedulable(PlacementGap.NO_ELIGIBLE_POD));
    }

    @Test
    void decide_matchingPodsAllFull_reportsNoCapacityNotNoEligiblePod() {
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                labelled("plain", 0, 10000, Map.of(), false),
                labelled("gpu-pod", 100, 100, Map.of("gpu", "true"), false)));

        PlacementDecision decision = placement.decide(
                requiring("trainer", 5, Map.of("gpu", "true")), PlacementTrigger.CREATE);

        assertThat(decision).as("the right kind exists and is full — scale it out")
                .isEqualTo(new PlacementDecision.Unschedulable(PlacementGap.NO_CAPACITY));
    }

    @Test
    void decide_exclusivePod_isNotUsedForASelectorlessProject() {
        givenSelfPod(100, 100);
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                labelled("reserved", 0, 100, Map.of("gpu", "true"), true)));

        PlacementDecision decision = placement.decide(project("ordinary", 1),
                PlacementTrigger.DISTRIBUTOR);

        assertThat(decision).isEqualTo(
                new PlacementDecision.Unschedulable(PlacementGap.NO_ELIGIBLE_POD));
    }

    @Test
    void decide_localPreference_doesNotOverrideEligibility() {
        // This pod has room but the wrong labels — preferring local here would
        // run the project exactly where its selector excludes it.
        when(clusterService.selfPod()).thenReturn(Optional.of(
                labelled(SELF_NODE, 0, 100, Map.of(), false)));
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                labelled(SELF_NODE, 0, 100, Map.of(), false),
                labelled("gpu-pod", 90, 100, Map.of("gpu", "true"), false)));

        PlacementDecision decision = placement.decide(
                requiring("trainer", 5, Map.of("gpu", "true")), PlacementTrigger.CREATE);

        assertThat(((PlacementDecision.On) decision).pod().getNodeName()).isEqualTo("gpu-pod");
    }

    @Test
    void decideBatch_reservesOnlyOnPodsEachProjectCanUse() {
        when(clusterService.liveClusterPods()).thenReturn(List.of(
                labelled("plain", 0, 10, Map.of(), false),
                labelled("gpu-pod", 0, 10, Map.of("gpu", "true"), false)));

        List<PlacementDecision> decisions = placement.decideBatch(List.of(
                requiring("trainer", 10, Map.of("gpu", "true")),
                project("ordinary", 10)));

        assertThat(((PlacementDecision.On) decisions.get(0)).pod().getNodeName())
                .isEqualTo("gpu-pod");
        assertThat(((PlacementDecision.On) decisions.get(1)).pod().getNodeName())
                .as("the gpu reservation must not consume the plain pod's room")
                .isEqualTo("plain");
    }

    // ─── isEligibleHere — the attach paths' question ─────────────────

    @Test
    void isEligibleHere_unregisteredPod_isTrueBecauseUnknownIsNotForbidden() {
        assertThat(placement.isEligibleHere(requiring("trainer", 1, Map.of("gpu", "true"))))
                .isTrue();
    }

    @Test
    void isEligibleHere_wrongLabels_isFalse() {
        when(clusterService.selfPod()).thenReturn(Optional.of(
                labelled(SELF_NODE, 0, 100, Map.of("gpu", "false"), false)));

        assertThat(placement.isEligibleHere(requiring("trainer", 1, Map.of("gpu", "true"))))
                .isFalse();
    }

    @Test
    void isEligibleHere_ignoresCapacity() {
        // A full pod is still *eligible*. The attach paths must not refuse a
        // waiting user over a soft score cap.
        when(clusterService.selfPod()).thenReturn(Optional.of(
                labelled(SELF_NODE, 100, 100, Map.of("gpu", "true"), false)));

        assertThat(placement.isEligibleHere(requiring("trainer", 50, Map.of("gpu", "true"))))
                .isTrue();
    }

    @Test
    void isEligibleHere_podlessProject_isAlwaysTrue() {
        when(clusterService.selfPod()).thenReturn(Optional.of(
                labelled(SELF_NODE, 0, 100, Map.of(), true)));
        ProjectDocument podless = ProjectDocument.builder()
                .tenantId("acme").name("_user_wile.coyote")
                .placementSelector(new HashMap<>(Map.of("gpu", "true")))
                .build();

        assertThat(placement.isEligibleHere(podless))
                .as("podless projects live wherever the WS lands — even on an exclusive pod")
                .isTrue();
    }

    @Test
    void isEligibleHere_byName_unknownProjectIsTrue() {
        when(projectService.findByTenantAndName("acme", "ghost")).thenReturn(Optional.empty());

        assertThat(placement.isEligibleHere("acme", "ghost"))
                .as("nothing to refuse — the caller's not-found path reports it")
                .isTrue();
    }

    @Test
    void isEligibleHere_byName_resolvesTheDocument() {
        when(clusterService.selfPod()).thenReturn(Optional.of(
                labelled(SELF_NODE, 0, 100, Map.of(), false)));
        when(projectService.findByTenantAndName("acme", "trainer"))
                .thenReturn(Optional.of(requiring("trainer", 1, Map.of("gpu", "true"))));

        assertThat(placement.isEligibleHere("acme", "trainer")).isFalse();
    }

    // ─── headroom ───────────────────────────────────────────────────

    @Test
    void localHeadroom_unregisteredPod_isUnbounded() {
        assertThat(placement.localHeadroom()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void localHeadroom_overbookedPod_isNegative() {
        givenSelfPod(120, 100);

        assertThat(placement.localHeadroom())
                .as("overbooking is a legitimate state — the cap is best-effort")
                .isEqualTo(-20);
    }
}
