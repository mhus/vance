package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.PodStatus;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class ClusterServiceTest {

    private static final String CLUSTER = "test-cluster";
    private static final String ENDPOINT = "10.0.0.7:8080";
    private static final String NODE_NAME = "maya-prosser";

    private BrainPodService brainPodService;
    private ProjectService projectService;
    private LocationService locationService;
    private ClusterNodeNameGenerator nameGenerator;
    private ClusterProperties properties;
    private ApplicationEventPublisher eventPublisher;
    private ClusterService service;
    private ClusterService singleNodeService;

    @BeforeEach
    void setUp() {
        brainPodService = mock(BrainPodService.class);
        projectService = mock(ProjectService.class);
        locationService = mock(LocationService.class);
        nameGenerator = mock(ClusterNodeNameGenerator.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        properties = new ClusterProperties();
        properties.setId(CLUSTER);
        properties.setHeartbeatInterval(Duration.ofSeconds(60));
        properties.setStaleAfter(Duration.ofMinutes(2));
        properties.setRegistrationMaxRetries(5);

        when(locationService.getPodAddress()).thenReturn(ENDPOINT);
        when(locationService.refreshPodAddress()).thenReturn(ENDPOINT);
        when(projectService.findByHomePodId(any())).thenReturn(List.of());
        when(nameGenerator.generate()).thenReturn(NODE_NAME);

        service = new ClusterService(
                brainPodService,
                projectService,
                locationService,
                nameGenerator,
                properties,
                new ClusterTimeWindows(properties),
                eventPublisher,
                () -> true);
        ReflectionTestUtils.setField(service, "buildVersion", "1.0.0-test");
        singleNodeService = new ClusterService(
                brainPodService,
                projectService,
                locationService,
                nameGenerator,
                properties,
                new ClusterTimeWindows(properties),
                eventPublisher,
                new SingleNodePeerPolicy());
        ReflectionTestUtils.setField(singleNodeService, "buildVersion", "1.0.0-test");
    }

    @Test
    void onApplicationReady_writesStartingThenRunning() {
        service.onApplicationReady();

        verify(brainPodService, times(1)).register(any(BrainPodDocument.class));
        verify(brainPodService, times(1))
                .heartbeat(
                        eq(service.selfPodId()),
                        any(Instant.class),
                        eq(PodStatus.RUNNING),
                        eq(ENDPOINT),
                        anyList(),
                        anyInt(),
                        anyInt(),
                        anyInt());
        assertThat(service.selfNodeName()).isEqualTo("maya-prosser");
        assertThat(service.selfClusterId()).isEqualTo(CLUSTER);
    }

    @Test
    void onApplicationReady_collisionRetriesWithFreshGeneratedName() {
        when(nameGenerator.generate())
                .thenReturn("first-name") // initial pick
                .thenReturn("second-name"); // retry pick

        // First register call throws taken-name; second succeeds.
        BrainPodService.NodeNameTakenException taken = new BrainPodService.NodeNameTakenException("taken", null);
        when(brainPodService.register(any(BrainPodDocument.class)))
                .thenThrow(taken)
                .thenAnswer(inv -> inv.getArgument(0));

        service.onApplicationReady();

        assertThat(service.selfNodeName()).isEqualTo("second-name");
        verify(brainPodService, times(2)).register(any(BrainPodDocument.class));
    }

    @Test
    void onApplicationReady_explicitConfiguredName_isNotAutoRenamedOnCollision() {
        properties.setNodeName("maya-explicit");
        when(brainPodService.register(any(BrainPodDocument.class)))
                .thenThrow(new BrainPodService.NodeNameTakenException("taken", null));

        // Operator asked for "maya-explicit" — refuse to silently rename.
        // onApplicationReady catches and logs (graceful boot), so no
        // exception bubbles up; the observable contract is "not
        // registered + no auto-rename via the generator".
        service.onApplicationReady();

        verify(nameGenerator, never()).generate();
        // Registered flag stays off → next heartbeat is a no-op.
        service.heartbeat();
        verify(brainPodService, never())
                .heartbeat(any(), any(), eq(PodStatus.RUNNING), anyString(), anyList(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void onApplicationReady_announcesTheNewCandidateForPlacement() {
        service.onApplicationReady();

        verify(eventPublisher).publishEvent(any(PlacementInputChangedEvent.class));
    }

    @Test
    void failedRegistration_announcesNothing() {
        // A pod that is not in the registry cannot be selected, so a round
        // triggered by it would spend itself and change nothing.
        when(brainPodService.register(any(BrainPodDocument.class))).thenThrow(new IllegalStateException("mongo down"));

        service.onApplicationReady();

        verify(eventPublisher, never()).publishEvent(any(PlacementInputChangedEvent.class));
    }

    @Test
    void heartbeat_skippedWhenNotYetRegistered() {
        service.heartbeat();
        verify(brainPodService, never()).heartbeat(any(), any(), any(), any(), anyList(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void heartbeat_passesActiveProjectsAsTenantSlashName() {
        service.onApplicationReady();

        ProjectDocument p1 = ProjectDocument.builder()
                .tenantId("acme")
                .name("instant-hole")
                .homePodId(service.selfPodId())
                .build();
        ProjectDocument p2 = ProjectDocument.builder()
                .tenantId("acme")
                .name("rocket-skates")
                .homePodId(service.selfPodId())
                .build();
        when(projectService.findByHomePodId(service.selfPodId())).thenReturn(List.of(p1, p2));

        service.heartbeat();

        verify(brainPodService, atLeastOnce())
                .heartbeat(
                        eq(service.selfPodId()),
                        any(Instant.class),
                        eq(PodStatus.RUNNING),
                        eq(ENDPOINT),
                        eq(List.of("acme/instant-hole", "acme/rocket-skates")),
                        anyInt(),
                        anyInt(),
                        anyInt());
    }

    // ─── routing: ownership and liveness are two gates ──────────────

    private BrainPodDocument holderRow(PodStatus status) {
        return BrainPodDocument.builder()
                .clusterId(CLUSTER)
                .podId("pod-holder")
                .nodeName("ford-prefect")
                .endpoint("10.0.0.9:8080")
                .status(status)
                .lastHeartbeatAt(Instant.now())
                .build();
    }

    @Test
    void resolveEndpointByPodId_returnsEndpointForLiveHolder() {
        BrainPodDocument row = holderRow(PodStatus.RUNNING);
        when(brainPodService.findByPodId("pod-holder")).thenReturn(java.util.Optional.of(row));
        when(brainPodService.isStale(eq(row), any(Instant.class), any(Duration.class)))
                .thenReturn(false);

        assertThat(service.resolveEndpointByPodId("pod-holder")).contains("10.0.0.9:8080");
    }

    @Test
    void resolveEndpointByPodId_crashedHolderWithValidLease_resolvesToNothing() {
        // kill -9: the row stays at RUNNING with its old address while the
        // lease is still valid for up to leaseTtl. Ownership says "his",
        // liveness has to say "not any more" — otherwise every hop to this
        // project is a connect timeout until the lease expires.
        BrainPodDocument row = holderRow(PodStatus.RUNNING);
        when(brainPodService.findByPodId("pod-holder")).thenReturn(java.util.Optional.of(row));
        when(brainPodService.isStale(eq(row), any(Instant.class), eq(Duration.ofMinutes(2))))
                .thenReturn(true);

        assertThat(service.resolveEndpointByPodId("pod-holder")).isEmpty();
    }

    @Test
    void resolveEndpointByPodId_stoppedHolder_resolvesToNothing() {
        BrainPodDocument row = holderRow(PodStatus.STOPPED);
        when(brainPodService.findByPodId("pod-holder")).thenReturn(java.util.Optional.of(row));

        assertThat(service.resolveEndpointByPodId("pod-holder")).isEmpty();
    }

    @Test
    void heartbeat_recoversWhenRowMissing() {
        service.onApplicationReady();
        when(brainPodService.heartbeat(any(), any(), any(), any(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("row missing"))
                .thenAnswer(inv -> null);

        service.heartbeat();

        // The recovery path re-runs onApplicationReady, which calls register again.
        verify(brainPodService, atLeastOnce()).register(any(BrainPodDocument.class));
    }

    // ─── is the holder of a lease gone? ──────────────────────────────────────

    private void givenPodRow(String podId, PodStatus status, Instant lastHeartbeat) {
        BrainPodDocument row = BrainPodDocument.builder()
                .podId(podId)
                .status(status)
                .endpoint("10.0.0.7:9990")
                .lastHeartbeatAt(lastHeartbeat)
                .build();
        when(brainPodService.findByPodId(podId)).thenReturn(java.util.Optional.of(row));
        when(brainPodService.isStale(any(BrainPodDocument.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(inv -> {
                    BrainPodDocument doc = inv.getArgument(0);
                    Duration staleAfter = inv.getArgument(2);
                    return doc.getLastHeartbeatAt() != null
                            && doc.getLastHeartbeatAt().isBefore(Instant.now().minus(staleAfter));
                });
    }

    @Test
    void aHolderWhoseRowStoppedBeating_isGone() {
        service.onApplicationReady();
        givenPodRow("pod-dead", PodStatus.RUNNING, Instant.now().minus(Duration.ofMinutes(10)));

        assertThat(service.isHolderGone("pod-dead")).isTrue();
    }

    @Test
    void aHolderWhoseRowIsMissing_isGone() {
        service.onApplicationReady();
        when(brainPodService.findByPodId("pod-purged")).thenReturn(java.util.Optional.empty());

        assertThat(service.isHolderGone("pod-purged")).isTrue();
    }

    @Test
    void aStoppedHolder_isGone() {
        service.onApplicationReady();
        givenPodRow("pod-stopped", PodStatus.STOPPED, Instant.now());

        assertThat(service.isHolderGone("pod-stopped")).isTrue();
    }

    @Test
    void aHolderThatBeatsRecently_isNotGone() {
        service.onApplicationReady();
        givenPodRow("pod-alive", PodStatus.RUNNING, Instant.now());

        assertThat(service.isHolderGone("pod-alive")).isFalse();
    }

    @Test
    void aLiveHolderWithoutAnEndpoint_isNotGone() {
        service.onApplicationReady();
        BrainPodDocument row = BrainPodDocument.builder()
                .podId("pod-no-endpoint")
                .status(PodStatus.RUNNING)
                .endpoint("")
                .lastHeartbeatAt(Instant.now())
                .build();
        when(brainPodService.findByPodId("pod-no-endpoint")).thenReturn(java.util.Optional.of(row));
        when(brainPodService.isStale(any(BrainPodDocument.class), any(Instant.class), any(Duration.class)))
                .thenReturn(false);

        assertThat(service.isHolderGone("pod-no-endpoint")).isFalse();
    }

    @Test
    void aPodThatHasNotRegisteredItself_doesNotJudgeTheOthers() {
        // Not onApplicationReady(): an empty registry read on a booting pod must not read as "everybody is gone".
        when(brainPodService.findByPodId("pod-other")).thenReturn(java.util.Optional.empty());

        assertThat(service.isHolderGone("pod-other")).isFalse();
    }

    // ─── single-node edition: every pod but this one is stale ────────────────

    @Test
    void singleNode_aPeerThatBeatsRecently_resolvesToNoEndpoint() {
        singleNodeService.onApplicationReady();
        givenPodRow("pod-peer", PodStatus.RUNNING, Instant.now());

        assertThat(singleNodeService.resolveEndpointByPodId("pod-peer")).isEmpty();
    }

    @Test
    void singleNode_aPeerThatBeatsRecently_isGone() {
        // The restart case: the previous instance's row is still inside its
        // heartbeat window, and its projects must be takeable right away.
        singleNodeService.onApplicationReady();
        givenPodRow("pod-previous-instance", PodStatus.RUNNING, Instant.now());

        assertThat(singleNodeService.isHolderGone("pod-previous-instance")).isTrue();
    }

    @Test
    void singleNode_aPeerThatBeatsRecently_isStale() {
        BrainPodDocument peer = BrainPodDocument.builder()
                .podId("pod-peer")
                .status(PodStatus.RUNNING)
                .lastHeartbeatAt(Instant.now())
                .build();

        assertThat(singleNodeService.isStale(peer, Instant.now())).isTrue();
    }

    @Test
    void singleNode_thePodItself_isNotIgnored() {
        singleNodeService.onApplicationReady();
        givenPodRow(singleNodeService.selfPodId(), PodStatus.RUNNING, Instant.now());

        assertThat(singleNodeService.isHolderGone(singleNodeService.selfPodId()))
                .isFalse();
        assertThat(singleNodeService.resolveEndpointByPodId(singleNodeService.selfPodId()))
                .isPresent();
    }

    @Test
    void singleNode_onlyThePodItselfIsLive() {
        singleNodeService.onApplicationReady();
        String self = singleNodeService.selfNodeName();
        BrainPodDocument selfRow = BrainPodDocument.builder()
                .podId(singleNodeService.selfPodId())
                .nodeName(self)
                .status(PodStatus.RUNNING)
                .build();
        BrainPodDocument peerRow = BrainPodDocument.builder()
                .podId("pod-peer")
                .nodeName("other-node")
                .status(PodStatus.RUNNING)
                .build();
        when(brainPodService.listLiveClusterNodeNames(any(), any(Duration.class)))
                .thenReturn(java.util.Set.of(self, "other-node"));
        when(brainPodService.listCluster(CLUSTER)).thenReturn(List.of(selfRow, peerRow));

        assertThat(singleNodeService.liveClusterNodeNames()).containsExactly(self);
        assertThat(singleNodeService.liveClusterPods()).containsExactly(selfRow);
    }

    @Test
    void singleNode_aLivePeerAtRegistration_doesNotBlockTheBoot() {
        BrainPodDocument peer = BrainPodDocument.builder()
                .podId("pod-peer")
                .nodeName("other-node")
                .status(PodStatus.RUNNING)
                .lastHeartbeatAt(Instant.now())
                .build();
        when(brainPodService.listCluster(CLUSTER)).thenReturn(List.of(peer));
        when(brainPodService.isStale(any(BrainPodDocument.class), any(Instant.class), any(Duration.class)))
                .thenReturn(false);

        singleNodeService.onApplicationReady();

        assertThat(singleNodeService.isRegistered()).isTrue();
    }

    @Test
    void multiNode_aPeerThatBeatsRecently_stillCounts() {
        service.onApplicationReady();
        givenPodRow("pod-peer", PodStatus.RUNNING, Instant.now());

        assertThat(service.isHolderGone("pod-peer")).isFalse();
        assertThat(service.resolveEndpointByPodId("pod-peer")).isPresent();
    }
}
