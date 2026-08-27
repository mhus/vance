package de.mhus.vance.anus.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.anus.cluster.ProjectClusterService.DrainVerdict;
import de.mhus.vance.anus.cluster.ProjectClusterService.Holder;
import de.mhus.vance.anus.cluster.ProjectClusterService.Placement;
import de.mhus.vance.anus.cluster.ProjectClusterService.PlacementOutcome;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The situations these operations distinguish — the part that used to be
 * reachable only by reading the command's text output.
 */
class ProjectClusterServiceTest {

    private static final String HOME = "/internal/cluster/projects/home";
    private static final String HELD_BODY =
            "{\"podId\":\"p-1\",\"endpoint\":\"10.0.0.7:9990\",\"nodeName\":\"pod-a\"}";

    private final AnusBrainClient client = mock(AnusBrainClient.class);
    private final ProjectClusterService service = new ProjectClusterService(client);

    // ─── home ───────────────────────────────────────────────────────────────

    @Test
    void home_readsTheHolderOutOfTheAnswer() {
        when(client.internal(contains(HOME), eq("GET"), any())).thenReturn(new Response(200, HELD_BODY));

        var home = service.home("acme", "p1");

        assertThat(home.holder()).isEqualTo(Holder.HELD);
        assertThat(home.nodeName()).isEqualTo("pod-a");
        assertThat(home.endpoint()).isEqualTo("10.0.0.7:9990");
    }

    @Test
    void home_a404_isNobodyHoldsIt_andKeepsTheReason() {
        // The reason matters: "never placed" and "podless, lives wherever the
        // WS landed" are both 404 and mean different things to a caller.
        when(client.internal(contains(HOME), eq("GET"), any()))
                .thenReturn(new Response(404, "podless project — holds no lease"));

        var home = service.home("acme", "p1");

        assertThat(home.holder()).isEqualTo(Holder.NONE);
        assertThat(home.detail()).contains("podless");
    }

    @Test
    void home_anErrorIsUnreachable_notFree() {
        when(client.internal(contains(HOME), eq("GET"), any())).thenReturn(new Response(500, "boom"));

        var home = service.home("acme", "p1");

        assertThat(home.holder()).isEqualTo(Holder.UNREACHABLE);
        assertThat(home.holder().isUnknown()).isTrue();
    }

    @Test
    void home_anUnparsableSuccessIsItsOwnCase() {
        // Distinct from UNREACHABLE on purpose: the brain did answer, so the
        // operator's next step is different — and treating it as "nobody holds
        // it" would let a delete proceed against a project a pod is working on.
        when(client.internal(contains(HOME), eq("GET"), any()))
                .thenReturn(new Response(200, "not json at all"));

        var home = service.home("acme", "p1");

        assertThat(home.holder()).isEqualTo(Holder.UNREADABLE);
        assertThat(home.holder().isUnknown()).isTrue();
    }

    // ─── place ──────────────────────────────────────────────────────────────

    @Test
    void place_mapsEveryStatusTheEndpointDistinguishes() {
        assertThat(placeWith(200)).isEqualTo(PlacementOutcome.PLACED);
        assertThat(placeWith(409)).isEqualTo(PlacementOutcome.ALREADY_RUNNING);
        assertThat(placeWith(503)).isEqualTo(PlacementOutcome.UNSCHEDULABLE);
        assertThat(placeWith(502))
                .as("a chosen pod that could not be brought up is not the same as no pod")
                .isEqualTo(PlacementOutcome.BRING_FAILED);
        assertThat(placeWith(404)).isEqualTo(PlacementOutcome.NOT_FOUND);
        assertThat(placeWith(418)).isEqualTo(PlacementOutcome.ERROR);
    }

    private PlacementOutcome placeWith(int status) {
        when(client.internal(eq("/internal/cluster/place"), eq("POST"), any()))
                .thenReturn(new Response(status, "body"));
        return service.place("acme", "p1").outcome();
    }

    // ─── drain ──────────────────────────────────────────────────────────────

    @Test
    void drain_aimsTheReleaseAtTheHoldingPod() {
        givenHeld();
        when(client.internalAt(any(), eq("/internal/cluster/release"), eq("POST"), any()))
                .thenReturn(new Response(200, "released"));

        var outcome = service.drain("acme", "p1");

        assertThat(outcome.released()).isTrue();
        assertThat(outcome.placement()).isEqualTo(Placement.PLACED);
        // The release has to reach that pod, not any brain: it tears down
        // in-memory state that exists only there.
        verify(client).internalAt(eq("http://10.0.0.7:9990"), eq("/internal/cluster/release"),
                eq("POST"), any());
    }

    @Test
    void drain_nothingToHandOff_countsAsReleased() {
        when(client.internal(contains(HOME), eq("GET"), any()))
                .thenReturn(new Response(404, "no live lease"));

        var outcome = service.drain("acme", "p1");

        assertThat(outcome.released())
                .as("a project nobody holds is already in the state a drain aims for")
                .isTrue();
        assertThat(outcome.placement()).isEqualTo(Placement.NOT_PLACED);
        verify(client, never()).internalAt(any(), any(), any(), any());
    }

    @Test
    void drain_a409FromTheHolderIsUnknown_notReleased() {
        // The lease moved or expired between the lookup and the release. Not a
        // clean hand-off and not a safe "nobody owns it" — another pod may have
        // taken it, so a caller that needs the project quiet must stop.
        givenHeld();
        when(client.internalAt(any(), eq("/internal/cluster/release"), eq("POST"), any()))
                .thenReturn(new Response(409, "not the holder"));

        var outcome = service.drain("acme", "p1");

        assertThat(outcome.released()).isFalse();
        assertThat(outcome.placement()).isEqualTo(Placement.UNKNOWN);
    }

    @Test
    void drain_aFailedReleaseKeepsPlaced_soTheCallerKnowsSomeoneHasIt() {
        givenHeld();
        when(client.internalAt(any(), eq("/internal/cluster/release"), eq("POST"), any()))
                .thenReturn(new Response(500, "boom"));

        var outcome = service.drain("acme", "p1");

        assertThat(outcome.released()).isFalse();
        assertThat(outcome.placement()).isEqualTo(Placement.PLACED);
    }

    // ─── drain as a precondition ────────────────────────────────────────────

    @Test
    void drainBefore_skipped_touchesNothing() {
        var decision = service.drainBefore("acme", "p1", /* noDrain */ true, false);

        assertThat(decision.verdict()).isEqualTo(DrainVerdict.SKIPPED);
        assertThat(decision.abort()).isFalse();
        assertThat(decision.outcome()).isNull();
        verify(client, never()).internal(any(), any(), any());
    }

    @Test
    void drainBefore_aFailedHandOffBlocks() {
        givenHeld();
        when(client.internalAt(any(), any(), any(), any())).thenReturn(new Response(500, "boom"));

        var decision = service.drainBefore("acme", "p1", false, /* force */ false);

        assertThat(decision.verdict()).isEqualTo(DrainVerdict.BLOCKED);
        assertThat(decision.abort())
                .as("not knowing whether a pod is still working on it is exactly when "
                        + "proceeding is unsafe")
                .isTrue();
    }

    @Test
    void drainBefore_forceTurnsTheBlockIntoAWarning() {
        givenHeld();
        when(client.internalAt(any(), any(), any(), any())).thenReturn(new Response(500, "boom"));

        var decision = service.drainBefore("acme", "p1", false, /* force */ true);

        assertThat(decision.verdict()).isEqualTo(DrainVerdict.FORCED);
        assertThat(decision.abort()).isFalse();
    }

    @Test
    void drainBefore_wasPlaced_isFalseWhenNobodyHeldIt() {
        // The question a rename asks afterwards: place it again, or not? A
        // project nobody held has no state to restore, and placing it would
        // start something the rename did not ask for.
        when(client.internal(contains(HOME), eq("GET"), any()))
                .thenReturn(new Response(404, "no live lease"));

        var decision = service.drainBefore("acme", "p1", false, false);

        assertThat(decision.verdict()).isEqualTo(DrainVerdict.RELEASED);
        assertThat(decision.wasPlaced()).isFalse();
    }

    // ─── selector ───────────────────────────────────────────────────────────

    @Test
    void writePlacement_omitsWhatTheCallerDidNotMention() {
        // null means "leave it alone", and the endpoint replaces whatever it is
        // given — so sending an absent field as anything would overwrite it.
        when(client.internal(any(), any(), any())).thenReturn(new Response(200, "ok"));

        service.writePlacement("acme", "p1", null, 7);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).internal(eq("/internal/cluster/projects/placement"), eq("POST"),
                body.capture());
        assertThat(body.getValue()).contains("homeResourceScore")
                .doesNotContain("placementSelector");
    }

    @Test
    void writePlacement_anEmptySelectorIsSent_becauseThatIsHowItClears() {
        when(client.internal(any(), any(), any())).thenReturn(new Response(200, "ok"));

        service.writePlacement("acme", "p1", Map.of(), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).internal(any(), any(), body.capture());
        assertThat(body.getValue()).contains("placementSelector")
                .doesNotContain("homeResourceScore");
    }

    @Test
    void withoutKeys_reportsWhatWasNotThere() {
        ProjectDocument project = ProjectDocument.builder()
                .tenantId("acme").name("p1")
                .placementSelector(Map.of("gpu", "true", "region", "eu"))
                .build();

        var removal = ProjectClusterService.withoutKeys(project, List.of("gpu", "zone"));

        assertThat(removal.target()).containsExactly(Map.entry("region", "eu"));
        assertThat(removal.keysNotFound())
                .as("removing an absent key reaches the desired state, so it must not fail — "
                        + "but a typo has to be visible")
                .containsExactly("zone");
    }

    @Test
    void selectorOf_aProjectWithoutOne_isEmptyNotNull() {
        ProjectDocument project = ProjectDocument.builder().tenantId("acme").name("p1").build();

        assertThat(ProjectClusterService.selectorOf(project)).isEmpty();
    }

    private void givenHeld() {
        when(client.internal(contains(HOME), eq("GET"), any())).thenReturn(new Response(200, HELD_BODY));
    }
}
