package de.mhus.vance.addon.brain.nutrimat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The {@code runTurn} drain loop must process every drained inbox exactly
 * once. Live finding 2026-10-04: the loop ran {@code runTurnFor} twice on the
 * same batch — the original user task was re-processed as a fresh turn after
 * the exhausted error (the gate saw a user chat input and reopened, by
 * design), and background-event batches were discarded twice.
 *
 * <p>The gate path (continue-gate discard) returns before the LLM
 * machinery, so it is the observable seam: one discard narration per
 * drained batch means one turn per batch.
 */
class NutrimatDrainLoopTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ShootyGuardService guardService = mock(ShootyGuardService.class);
    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);
    private final ChatMessageService chatLog = mock(ChatMessageService.class);

    /** redbull-shaped nature: hard-stop gate active, everything else stubbed. */
    private final AbstractNutrimat engine =
            new AbstractNutrimat(
                    thinkProcessService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    guardService,
                    null) {
                @Override
                protected String natureId() {
                    return "redbull";
                }

                @Override
                protected String loopType() {
                    return "test loop";
                }

                @Override
                protected TurnOutcome runLoop(
                        ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
                    return TurnOutcome.terminal("unused — the gate discards before any loop", false);
                }

                @Override
                protected boolean exhaustedStopsUntilUserInput() {
                    return true;
                }
            };

    @BeforeEach
    void setUp() {
        when(ctx.chatMessageService()).thenReturn(chatLog);
    }

    /** A parked primary: hard-failure turn set the continue-gate marker. */
    private static ThinkProcessDocument parkedPrimary() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("p1");
        process.setTenantId("acme");
        process.setSessionId("s1");
        process.setEngineParams(Map.of("nutrimatState", Map.of("awaitingUserContinue", true)));
        return process;
    }

    private static SteerMessage event(String id) {
        return new SteerMessage.ProcessEvent(
                Instant.now(), null, id, ProcessEventType.EXEC_FINISHED, "done", null, null, null);
    }

    @Test
    void runTurn_backgroundBatch_isDiscardedExactlyOnce() {
        ThinkProcessDocument process = parkedPrimary();
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        when(ctx.drainPending()).thenReturn(List.of(event("c1"), event("c2")), List.of());

        engine.runTurn(process, ctx);

        // One turn per drained batch: the discard narration persists once,
        // and the process parks BLOCKED once. A second runTurnFor on the
        // same batch (the regression) would do both twice.
        ArgumentCaptor<ChatMessageDocument> saved = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(1)).append(saved.capture());
        assertThat(saved.getValue().getContent()).contains("discarded 2 background event(s)");
        verify(thinkProcessService, times(1)).updateStatus("p1", ThinkProcessStatus.BLOCKED);
    }

    @Test
    void runTurn_multipleBackgroundBatches_eachDiscardedOnce() {
        ThinkProcessDocument process = parkedPrimary();
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process));
        when(ctx.drainPending()).thenReturn(List.of(event("c1"), event("c2")), List.of(event("c3")), List.of());

        engine.runTurn(process, ctx);

        // The loop keeps draining until the queue is empty — every batch
        // gets its own single discard narration, none is processed twice.
        ArgumentCaptor<ChatMessageDocument> saved = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(2)).append(saved.capture());
        var contents = saved.getAllValues().stream()
                .map(ChatMessageDocument::getContent)
                .toList();
        assertThat(contents).anyMatch(c -> c.contains("discarded 2 background event(s)"));
        assertThat(contents).anyMatch(c -> c.contains("discarded 1 background event(s)"));
        verify(ctx, times(3)).drainPending();
    }

    @Test
    void runTurn_userKickOnClosedGate_resetsRunStateImmediately() {
        // Live finding 2026-10-04: after a hard-failure turn the state kept
        // the closed-gate markers and the cumulative turns counter until
        // the NEXT turn end — mid-run //nutrimat status showed a closed
        // loop that was already working. A user kick must reset the run
        // state (turns back to 0, gate open, outcome 'running') the moment
        // the loop reopens.
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("p3");
        process.setTenantId("acme");
        process.setSessionId("s1");
        process.setEngineParams(Map.of("nutrimatState", Map.of("awaitingUserContinue", true, "turns", 5)));
        when(thinkProcessService.findById("p3")).thenReturn(Optional.of(process));
        when(ctx.drainPending())
                .thenReturn(List.of(new SteerMessage.UserChatInput(Instant.now(), null, "wile", "continue")))
                .thenReturn(List.of());

        // The turn proceeds into the full shell and dies on the first
        // unstubbed dependency (chat factory) — the reset happened before
        // that, so the captured state IS the assertion.
        assertThatThrownBy(() -> engine.runTurn(process, ctx)).isInstanceOf(RuntimeException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(thinkProcessService).replaceEngineParams(eq("p3"), paramsCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> state =
                (Map<String, Object>) paramsCaptor.getValue().get("nutrimatState");
        assertThat(state)
                .containsEntry("turns", 0)
                .containsEntry("awaitingUserContinue", false)
                .containsEntry("lastOutcome", "running");

        // The reopen is visible in the working log — the run boundary the
        // user sees between two loop runs.
        ArgumentCaptor<ChatMessageDocument> saved = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatLog, times(2)).append(saved.capture());
        assertThat(saved.getAllValues().stream().map(ChatMessageDocument::getContent))
                .anyMatch(c -> c.contains("loop reopened by user input"));
    }

    @Test
    void runTurn_gateClosed_batchRunsAsTurn() {
        // Without the marker the gate must not fire — the batch runs as a
        // normal turn (and the discard narration never appears).
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("p2");
        process.setTenantId("acme");
        process.setSessionId("s1");
        when(thinkProcessService.findById("p2")).thenReturn(Optional.of(process));
        when(ctx.drainPending()).thenReturn(List.of(event("c1")));

        // The turn enters the full shell and dies on the first unstubbed
        // dependency — that failure IS the assertion: the gate did not
        // short-circuit it.
        assertThatThrownBy(() -> engine.runTurn(process, ctx)).isInstanceOf(RuntimeException.class);
        verify(chatLog, never()).append(any());
    }
}
