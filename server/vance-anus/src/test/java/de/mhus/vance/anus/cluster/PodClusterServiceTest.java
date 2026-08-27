package de.mhus.vance.anus.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.BrainCallException;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.anus.cluster.PodClusterService.PingResult;
import de.mhus.vance.anus.cluster.PodClusterService.PruneReason;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.ClusterMasterStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The pod operations, and above all the two places where a decision used to
 * hang off a display string.
 */
class PodClusterServiceTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final BrainPodService brainPodService = mock(BrainPodService.class);
    private final ClusterMasterStore masterStore = mock(ClusterMasterStore.class);
    private final AnusBrainClient client = mock(AnusBrainClient.class);
    private final PodClusterService service =
            new PodClusterService(brainPodService, masterStore, client);

    private static BrainPodDocument pod(String podId, String node) {
        return BrainPodDocument.builder()
                .clusterId("default").podId(podId).nodeName(node)
                .endpoint("10.0.0.7:9990")
                .lastHeartbeatAt(Instant.now())
                .build();
    }

    // ─── resolve ────────────────────────────────────────────────────────────

    @Test
    void resolve_takesAPodIdDirectly() {
        BrainPodDocument row = pod("p-1", "pod-a");
        when(brainPodService.findByPodId("p-1")).thenReturn(Optional.of(row));

        assertThat(service.resolve("p-1")).isSameAs(row);
        verify(brainPodService, never()).listAll();
    }

    @Test
    void resolve_fallsBackToTheNodeName() {
        when(brainPodService.findByPodId("pod-a")).thenReturn(Optional.empty());
        when(brainPodService.listAll()).thenReturn(List.of(pod("p-1", "pod-a"), pod("p-2", "pod-b")));

        assertThat(service.resolve("pod-a").getPodId()).isEqualTo("p-1");
    }

    @Test
    void resolve_anAmbiguousNodeNameIsRefused_notGuessed() {
        // Picking the first hit would write to a pod the caller did not name.
        when(brainPodService.findByPodId("pod-a")).thenReturn(Optional.empty());
        when(brainPodService.listAll()).thenReturn(List.of(
                BrainPodDocument.builder().clusterId("dev").podId("p-1").nodeName("pod-a").build(),
                BrainPodDocument.builder().clusterId("prod").podId("p-2").nodeName("pod-a").build()));

        assertThatThrownBy(() -> service.resolve("pod-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exists in 2 clusters")
                .hasMessageContaining("use the podId");
    }

    @Test
    void resolve_nothingMatching_saysBothNamesItTried() {
        when(brainPodService.findByPodId(any())).thenReturn(Optional.empty());
        when(brainPodService.listAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolve("nope"))
                .hasMessageContaining("podId or nodeName");
    }

    // ─── patch ──────────────────────────────────────────────────────────────

    @Test
    void patch_sendsOnlyWhatTheCallerAddressed() {
        when(client.internal(any(), any(), any())).thenReturn(new Response(200, "ok"));

        service.patch("p-1", null, true, null, false);

        assertThat(sentBody()).contains("exclusive")
                .doesNotContain("labels")
                .doesNotContain("maxScoreOverride")
                .doesNotContain("clearMaxScoreOverride");
    }

    @Test
    void patch_anEmptyLabelMapIsSent_becauseThatIsHowItClears() {
        when(client.internal(any(), any(), any())).thenReturn(new Response(200, "ok"));

        service.patch("p-1", Map.of(), null, null, false);

        assertThat(sentBody()).contains("labels");
    }

    @Test
    void patch_clearingTheOverrideIsItsOwnField_notANull() {
        // "drop the override" and "I am not talking about the override" are
        // different statements, and null is already taken by the second.
        when(client.internal(any(), any(), any())).thenReturn(new Response(200, "ok"));

        service.patch("p-1", null, null, null, true);

        assertThat(sentBody()).contains("clearMaxScoreOverride")
                .doesNotContain("\"maxScoreOverride\"");
    }

    @Test
    void patch_serialisesLabelValues_soAQuoteCannotBreakTheWire() {
        when(client.internal(any(), any(), any())).thenReturn(new Response(200, "ok"));

        service.patch("p-1", Map.of("note", "a \"quoted\" value"), null, null, false);

        assertThat(sentBody())
                .as("a hand-built body would send this as malformed JSON and come back "
                        + "as an unexplained 400")
                .contains("\\\"quoted\\\"");
    }

    private String sentBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).internal(eq("/internal/cluster/pods/p-1/placement"), eq("PATCH"),
                body.capture());
        return body.getValue();
    }

    // ─── labels ─────────────────────────────────────────────────────────────

    @Test
    void labelsWithout_reportsWhatWasNotThere() {
        BrainPodDocument row = BrainPodDocument.builder().podId("p-1").nodeName("pod-a")
                .labels(new java.util.LinkedHashMap<>(Map.of("gpu", "true", "region", "eu")))
                .build();

        var removal = PodClusterService.labelsWithout(row, List.of("gpu", "zone"));

        assertThat(removal.labels()).containsExactly(Map.entry("region", "eu"));
        assertThat(removal.keysNotFound())
                .as("removing an absent label reaches the desired state, so it must not "
                        + "fail — but a typo has to be visible")
                .containsExactly("zone");
    }

    @Test
    void labelsWith_keepsTheRest() {
        BrainPodDocument row = BrainPodDocument.builder().podId("p-1").nodeName("pod-a")
                .labels(new java.util.LinkedHashMap<>(Map.of("region", "eu")))
                .build();

        assertThat(PodClusterService.labelsWith(row, Map.of("gpu", "true")))
                .containsOnly(Map.entry("region", "eu"), Map.entry("gpu", "true"));
    }

    // ─── ping ───────────────────────────────────────────────────────────────

    @Test
    void ping_a200FromTheWrongPodIsStale_notOk() {
        // The whole point of the identity check: an HTTP 200 only proves that
        // something is on this address.
        when(client.getAt(any(), any(), any()))
                .thenReturn(new Response(200, "{\"podId\":\"other\",\"nodeName\":\"pod-b\"}"));

        var ping = service.pingOne(pod("p-1", "pod-a"), "_vance");

        assertThat(ping.result()).isEqualTo(PingResult.STALE);
        assertThat(ping.respondingNodeName()).isEqualTo("pod-b");
    }

    @Test
    void ping_theRightPodIsOk() {
        when(client.getAt(any(), any(), any()))
                .thenReturn(new Response(200, "{\"podId\":\"p-1\",\"nodeName\":\"pod-a\"}"));

        assertThat(service.pingOne(pod("p-1", "pod-a"), "_vance").result())
                .isEqualTo(PingResult.OK);
    }

    @Test
    void ping_anErrorStatusKeepsItsCode() {
        when(client.getAt(any(), any(), any())).thenReturn(new Response(503, "unavailable"));

        var ping = service.pingOne(pod("p-1", "pod-a"), "_vance");

        assertThat(ping.result()).isEqualTo(PingResult.HTTP_ERROR);
        assertThat(ping.statusCode())
                .as("\"HTTP\" without the code says nothing an operator can act on")
                .isEqualTo(503);
    }

    @Test
    void ping_aTransportFailureIsUnreachable() {
        when(client.getAt(any(), any(), any()))
                .thenThrow(new BrainCallException("connect timed out", null));

        assertThat(service.pingOne(pod("p-1", "pod-a"), "_vance").result())
                .isEqualTo(PingResult.UNREACHABLE);
    }

    @Test
    void ping_aRowWithoutAnEndpointIsSkipped_notCalled() {
        BrainPodDocument row = BrainPodDocument.builder()
                .clusterId("default").podId("p-1").nodeName("pod-a").build();

        assertThat(service.pingOne(row, "_vance").result()).isEqualTo(PingResult.SKIPPED);
        verify(client, never()).getAt(any(), any(), any());
    }

    // ─── prune ──────────────────────────────────────────────────────────────

    @Test
    void prune_aStaleHeartbeatIsACandidate() {
        BrainPodDocument old = BrainPodDocument.builder()
                .clusterId("default").podId("p-1").nodeName("pod-a")
                .lastHeartbeatAt(Instant.now().minus(Duration.ofHours(1)))
                .build();

        var candidates = service.pruneCandidates(List.of(old), STALE_AFTER, false, "_vance");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).reason()).isEqualTo(PruneReason.STALE_HEARTBEAT);
    }

    @Test
    void prune_aFreshRegistrationWithoutABeatIsSpared() {
        // "no heartbeat" can mean "registered a second ago and has not ticked
        // yet", so the grace window is anchored on bootedAt.
        BrainPodDocument fresh = BrainPodDocument.builder()
                .clusterId("default").podId("p-1").nodeName("pod-a")
                .bootedAt(Instant.now())
                .build();

        assertThat(service.pruneCandidates(List.of(fresh), STALE_AFTER, false, "_vance"))
                .isEmpty();
    }

    @Test
    void prune_aBeatlessRowPastTheGraceWindowGoes() {
        BrainPodDocument stuck = BrainPodDocument.builder()
                .clusterId("default").podId("p-1").nodeName("pod-a")
                .bootedAt(Instant.now().minus(Duration.ofHours(1)))
                .build();

        var candidates = service.pruneCandidates(List.of(stuck), STALE_AFTER, false, "_vance");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).reason()).isEqualTo(PruneReason.NO_HEARTBEAT);
    }

    @Test
    void prune_withoutProbe_makesNoHttpCalls() {
        // An offline prune has to work: the probe is opt-in, and a fresh pod
        // passes the cheap check first.
        service.pruneCandidates(List.of(pod("p-1", "pod-a")), STALE_AFTER, false, "_vance");

        verify(client, never()).getAt(any(), any(), any());
    }

    @Test
    void prune_theProbeTurnsAMismatchIntoACandidate() {
        // This is the case that used to hang off the string "STALE": a rename
        // of that table cell would have quietly stopped pruning these rows.
        when(client.getAt(any(), any(), any()))
                .thenReturn(new Response(200, "{\"podId\":\"other\",\"nodeName\":\"pod-b\"}"));

        var candidates = service.pruneCandidates(
                List.of(pod("p-1", "pod-a")), STALE_AFTER, /* probe */ true, "_vance");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).reason()).isEqualTo(PruneReason.LIVE_MISMATCH);
        assertThat(candidates.get(0).detail()).contains("other");
    }

    @Test
    void prune_theProbeAlsoCatchesAnUnreachablePod() {
        when(client.getAt(any(), any(), any()))
                .thenThrow(new BrainCallException("connect timed out", null));

        var candidates = service.pruneCandidates(
                List.of(pod("p-1", "pod-a")), STALE_AFTER, true, "_vance");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).reason()).isEqualTo(PruneReason.UNREACHABLE);
    }

    @Test
    void prune_deletesExactlyTheCandidates() {
        when(brainPodService.deleteByPodId(any())).thenReturn(1L);
        var candidates = List.of(
                new PodClusterService.PruneCandidate(
                        pod("p-1", "pod-a"), PruneReason.STALE_HEARTBEAT, "x", null),
                new PodClusterService.PruneCandidate(
                        pod("p-2", "pod-b"), PruneReason.STALE_HEARTBEAT, "x", null));

        assertThat(service.prune(candidates)).isEqualTo(2);
        verify(brainPodService).deleteByPodId("p-1");
        verify(brainPodService).deleteByPodId("p-2");
    }

    // ─── master lease ───────────────────────────────────────────────────────

    @Test
    void liveMasterPodIds_ignoresAnExpiredLease() {
        var lease = mock(de.mhus.vance.shared.cluster.ClusterMasterDocument.class);
        when(lease.getCurrentPodId()).thenReturn("p-1");
        when(lease.getLeaseUntil()).thenReturn(Instant.now().minusSeconds(60));
        when(masterStore.find("default")).thenReturn(Optional.of(lease));

        assertThat(service.liveMasterPodIds(List.of(pod("p-1", "pod-a"))))
                .as("staleness is observer-derived — an unrenewed lease names a holder "
                        + "that is gone")
                .containsExactly(Map.entry("default", ""));
    }

    @Test
    void liveMasterPodIds_queriesEachClusterOnce() {
        var lease = mock(de.mhus.vance.shared.cluster.ClusterMasterDocument.class);
        when(lease.getCurrentPodId()).thenReturn("p-1");
        when(lease.getLeaseUntil()).thenReturn(Instant.now().plusSeconds(60));
        when(masterStore.find("default")).thenReturn(Optional.of(lease));

        var masters = service.liveMasterPodIds(
                List.of(pod("p-1", "pod-a"), pod("p-2", "pod-b"), pod("p-3", "pod-c")));

        assertThat(masters).containsExactly(Map.entry("default", "p-1"));
        verify(masterStore).find("default");
    }
}
