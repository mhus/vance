package de.mhus.vance.brain.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.vogon.StrategyState;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ThinkProcessRunSourceTest {

    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final ThinkEngineService engines = mock(ThinkEngineService.class);
    private final de.mhus.vance.brain.session.SessionLifecycleService lifecycle =
            mock(de.mhus.vance.brain.session.SessionLifecycleService.class);
    private final de.mhus.vance.brain.thinkengine.ProcessEventEmitter emitter =
            mock(de.mhus.vance.brain.thinkengine.ProcessEventEmitter.class);
    private final ThinkProcessRunSource source = new ThinkProcessRunSource(
            processes, engines, JsonMapper.builder().build(), lifecycle, emitter);

    // Built outside any when(...) — stubbing a mock while another stubbing
    // is open is what Mockito calls UnfinishedStubbing.
    private final ThinkEngine planned = engine(true);
    private final ThinkEngine flat = engine(false);

    @Test
    void listsOnlyEnginesThatDeclareThemselvesPlanShaped() {
        // The filter is the engine's own answer, not a name list here —
        // otherwise every new engine needs an edit in the run view.
        when(processes.findByProject(any(), any(), anyInt()))
                .thenReturn(List.of(process("p1", "vogon"), process("p2", "ford")));
        when(engines.resolve("vogon")).thenReturn(Optional.of(planned));
        when(engines.resolve("ford")).thenReturn(Optional.of(flat));

        assertThat(source.list("acme", "proj", 10))
                .singleElement()
                .satisfies(r -> assertThat(r.getRunId()).isEqualTo("process:p1"));
    }

    @Test
    void anUnknownEngineIsNotShown() {
        when(processes.findByProject(any(), any(), anyInt()))
                .thenReturn(List.of(process("p1", "ghost")));
        when(engines.resolve("ghost")).thenReturn(Optional.empty());

        assertThat(source.list("acme", "proj", 10)).isEmpty();
    }

    @Test
    void mapsTheSevenStatusesOntoTheSharedVocabulary() {
        assertThat(statusOf(ThinkProcessStatus.RUNNING, null)).isEqualTo(RunStatus.RUNNING);
        // Idle and blocked are one situation for a reader: waiting on
        // something outside the run.
        assertThat(statusOf(ThinkProcessStatus.IDLE, null)).isEqualTo(RunStatus.WAITING);
        assertThat(statusOf(ThinkProcessStatus.BLOCKED, null)).isEqualTo(RunStatus.WAITING);
        assertThat(statusOf(ThinkProcessStatus.SUSPENDED, null)).isEqualTo(RunStatus.PAUSED);
        assertThat(statusOf(ThinkProcessStatus.CLOSED, CloseReason.DONE)).isEqualTo(RunStatus.DONE);
        assertThat(statusOf(ThinkProcessStatus.CLOSED, CloseReason.STALE)).isEqualTo(RunStatus.FAILED);
        assertThat(statusOf(ThinkProcessStatus.CLOSED, CloseReason.STOPPED)).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    void readsPhasesAndWorkersOutOfTheStrategyState() {
        // Built as a real StrategyState and converted the way Vogon
        // persists it — a hand-written map omits the primitive fields and
        // would not deserialise, which is a property of the test data
        // rather than of the code under test.
        StrategyState state = StrategyState.builder()
                .currentPhasePath(new java.util.ArrayList<>(List.of("review")))
                .phaseHistory(new java.util.ArrayList<>(List.of("plan", "build")))
                .workerProcessIds(new java.util.LinkedHashMap<>(Map.of("build", "w-1")))
                .pendingCheckpoint(StrategyState.PendingCheckpoint.builder()
                        .phaseName("review").inboxItemId("inbox-9").build())
                .build();
        ThinkProcessDocument p = process("p1", "vogon");
        p.setEngineParams(Map.of("strategyState",
                JsonMapper.builder().build().convertValue(state, Map.class)));
        when(processes.findById("p1")).thenReturn(Optional.of(p));
        when(engines.resolve("vogon")).thenReturn(Optional.of(planned));

        var detail = source.get("acme", "proj", "p1").orElseThrow();

        assertThat(detail.getSteps()).extracting("name").containsExactly("plan", "build", "review");
        assertThat(detail.getSummary().getStep()).isEqualTo("review");
        assertThat(detail.getChildren()).singleElement()
                .satisfies(c -> assertThat(c.getRunId()).isEqualTo("process:w-1"));
        assertThat(detail.getWaitingOnInboxItemId()).isEqualTo("inbox-9");
    }

    @Test
    void aProcessOfAnotherProjectReadsAsAbsent() {
        ThinkProcessDocument p = process("p1", "vogon");
        p.setProjectId("other");
        when(processes.findById("p1")).thenReturn(Optional.of(p));

        assertThat(source.get("acme", "proj", "p1")).isEmpty();
    }

    private RunStatus statusOf(ThinkProcessStatus status, CloseReason reason) {
        ThinkProcessDocument p = process("p1", "vogon");
        p.setStatus(status);
        p.setCloseReason(reason);
        when(processes.findById("p1")).thenReturn(Optional.of(p));
        when(engines.resolve("vogon")).thenReturn(Optional.of(planned));
        return source.get("acme", "proj", "p1").orElseThrow().getSummary().getStatus();
    }

    @Test
    void offersActionsThatFitTheState() {
        assertThat(actionsOf(ThinkProcessStatus.RUNNING))
                .containsExactlyInAnyOrder(RunAction.PAUSE, RunAction.STOP);
        assertThat(actionsOf(ThinkProcessStatus.PAUSED))
                .containsExactlyInAnyOrder(RunAction.RESUME, RunAction.STOP);
        // A session-owned hold is not the user's to lift here; offering
        // RESUME would give the same state two owners.
        assertThat(actionsOf(ThinkProcessStatus.SUSPENDED)).containsExactly(RunAction.STOP);
        assertThat(actionsOf(ThinkProcessStatus.CLOSED)).isEmpty();
    }

    @Test
    void performRoutesToTheServiceThatTheWsHandlersAlsoUse() {
        ThinkProcessDocument p = process("p1", "vogon");
        when(processes.findById("p1")).thenReturn(Optional.of(p));

        source.perform("acme", "proj", "p1", RunAction.PAUSE, "why");

        verify(lifecycle).pauseProcess(p);
    }

    @Test
    void anActionTheStateDoesNotOfferIsANoOp() {
        // The button was rendered from a snapshot; by the time the click
        // lands the run may legitimately have finished. Erroring would
        // punish the user for a race they cannot see.
        ThinkProcessDocument p = process("p1", "vogon");
        p.setStatus(ThinkProcessStatus.CLOSED);
        when(processes.findById("p1")).thenReturn(Optional.of(p));

        source.perform("acme", "proj", "p1", RunAction.STOP, "why");

        verify(lifecycle, never()).stopProcess(any());
    }

    @Test
    void performOnAForeignProjectIsRefused() {
        ThinkProcessDocument p = process("p1", "vogon");
        p.setProjectId("other");
        when(processes.findById("p1")).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> source.perform("acme", "proj", "p1", RunAction.STOP, "why"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(lifecycle, never()).stopProcess(any());
    }

    private java.util.Set<RunAction> actionsOf(ThinkProcessStatus status) {
        ThinkProcessDocument p = process("p1", "vogon");
        p.setStatus(status);
        when(processes.findById("p1")).thenReturn(Optional.of(p));
        return source.allowedActions("acme", "proj", "p1");
    }

    private static ThinkProcessDocument process(String id, String engine) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId(id);
        p.setTenantId("acme");
        p.setProjectId("proj");
        p.setSessionId("sess-1");
        p.setName(id);
        p.setThinkEngine(engine);
        p.setStatus(ThinkProcessStatus.RUNNING);
        return p;
    }

    private static ThinkEngine engine(boolean planShaped) {
        ThinkEngine e = mock(ThinkEngine.class);
        when(e.planShaped()).thenReturn(planShaped);
        return e;
    }
}
