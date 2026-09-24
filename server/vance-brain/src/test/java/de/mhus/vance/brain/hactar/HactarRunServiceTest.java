package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.hactar.phases.ExecutingPhase;
import de.mhus.vance.brain.hactar.phases.LoadingPhase;
import de.mhus.vance.brain.hactar.phases.ValidatingPhase;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link HactarRunService}: the background phase machine — kick, phase
 * progression with wakeups, terminal handling, and the stop paths
 * (planning/hactar-agent-identity.md §3.1). Phases are mocked; the runner
 * executes on the service's real background executor, so every test waits
 * for the runner to settle before asserting.
 */
class HactarRunServiceTest {

    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private HactarStateStore stateStore;
    private HactarProgressRing progressRing;
    private LoadingPhase loadingPhase;
    private ValidatingPhase validatingPhase;
    private ExecutingPhase executingPhase;
    private HactarRunService service;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        loadingPhase = mock(LoadingPhase.class);
        validatingPhase = mock(ValidatingPhase.class);
        executingPhase = mock(ExecutingPhase.class);
        stateStore = new HactarStateStore(
                thinkProcessService,
                tools.jackson.databind.json.JsonMapper.builder().build());
        progressRing = new HactarProgressRing();
        service = new HactarRunService(
                thinkProcessService,
                chatMessageService,
                stateStore,
                progressRing,
                new HactarConsoleLog(),
                loadingPhase,
                validatingPhase,
                executingPhase,
                tools.jackson.databind.json.JsonMapper.builder().build());

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setProjectId("proj-1");
    }

    @AfterEach
    void tearDown() {
        service.stop("proc-1");
    }

    /** Hand-rolled wait (no Awaitility dependency) — 10ms poll, 5s cap. */
    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout waiting for run to settle");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for run to settle", e);
            }
        }
    }

    /** Blocks until released; an interrupt (stop) breaks the wait, not the stub. */
    private static void blockUntil(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HactarState loadedState() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService, atLeastOnce()).replaceEngineParams(eq("proc-1"), captor.capture());
        Map<String, Object> latest =
                captor.getAllValues().get(captor.getAllValues().size() - 1);
        Object raw = latest.get(HactarEngine.STATE_KEY);
        return tools.jackson.databind.json.JsonMapper.builder().build().convertValue(raw, HactarState.class);
    }

    private List<String> pendingNotes() {
        ArgumentCaptor<PendingMessageDocument> captor = ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(thinkProcessService, atLeastOnce()).appendPending(eq("proc-1"), captor.capture(), eq(""));
        return captor.getAllValues().stream()
                .map(PendingMessageDocument::getContent)
                .toList();
    }

    private List<ChatMessageDocument> historyNotes() {
        ArgumentCaptor<ChatMessageDocument> captor = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService, atLeastOnce()).append(captor.capture());
        return captor.getAllValues();
    }

    private HactarState baseState() {
        return HactarState.builder()
                .scriptRef("scripts/x.js")
                .language("js")
                .status(HactarStatus.READY)
                .build();
    }

    // ──────────────────── Kick + phase progression ────────────────────

    @Test
    void start_runsPhasesToDone_withTerminalWakeup() {
        doAnswer(inv -> HactarStatus.EXECUTING).when(loadingPhase).execute(any(), any());
        doAnswer(inv -> {
                    HactarState s = inv.getArgument(0);
                    s.setExecutionResult(Map.of("ok", true));
                    s.setExecutionDurationMs(12);
                    return HactarStatus.DONE;
                })
                .when(executingPhase)
                .execute(any(), any());

        service.start(process, baseState());

        awaitUntil(() -> !service.isRunning("proc-1"));

        HactarState state = loadedState();
        assertThat(state.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(state.getRunStartedAtMs()).isNotNull();
        // The kick note is history-only (no agent turn), the terminal note
        // is the pending wake trigger.
        assertThat(historyNotes())
                .anyMatch(n -> n.getContent() != null && n.getContent().contains("run started"));
        assertThat(pendingNotes()).noneMatch(n -> n != null && n.contains("run started"));
        assertThat(pendingNotes()).anyMatch(n -> n != null && n.contains("run finished"));
        // History note + pending copy carry the same text (dedup contract:
        // the history note is the display copy, the pending copy only the trigger).
        assertThat(historyNotes()).isNotEmpty();
        assertThat(historyNotes().get(0).getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(historyNotes().get(0).getContent()).startsWith("[run] ");
        // The process is NOT closed by the runner — the engine's runTurn owns
        // the worker-form close (F1: terminal follows the spawn form).
        verify(thinkProcessService, org.mockito.Mockito.never()).closeProcess(any(), any());
    }

    @Test
    void start_loadingFailure_failsWithWakeupNamingSlartUpdate() {
        doAnswer(inv -> {
                    HactarState s = inv.getArgument(0);
                    s.setFailureReason("Script document not found: scripts/x.js");
                    return HactarStatus.FAILED;
                })
                .when(loadingPhase)
                .execute(any(), any());

        service.start(process, baseState());

        awaitUntil(() -> !service.isRunning("proc-1"));

        HactarState state = loadedState();
        assertThat(state.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(state.getFailureReason()).contains("not found");
        assertThat(pendingNotes()).anyMatch(n -> n != null && n.contains("run failed"));
        assertThat(pendingNotes()).anyMatch(n -> n != null && n.contains("Slart mode=Update"));
    }

    @Test
    void start_validatingBranchRunsWhenStateRequiresIt() {
        doAnswer(inv -> HactarStatus.VALIDATING).when(loadingPhase).execute(any(), any());
        doAnswer(inv -> HactarStatus.EXECUTING).when(validatingPhase).execute(any(), any());
        doAnswer(inv -> {
                    HactarState s = inv.getArgument(0);
                    s.setExecutionResult("done");
                    s.setExecutionDurationMs(5);
                    return HactarStatus.DONE;
                })
                .when(executingPhase)
                .execute(any(), any());

        HactarState state = baseState();
        state.setValidateBeforeRun(true);
        service.start(process, state);

        awaitUntil(() -> !service.isRunning("proc-1"));

        verify(loadingPhase).execute(any(), any());
        verify(validatingPhase).execute(any(), any());
        verify(executingPhase).execute(any(), any());
        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.DONE);
    }

    @Test
    void start_secondKickWhileRunning_refuses() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
                    blockUntil(release);
                    return HactarStatus.EXECUTING;
                })
                .when(loadingPhase)
                .execute(any(), any());

        service.start(process, baseState());
        awaitUntil(() -> service.isRunning("proc-1"));

        assertThatThrownBy(() -> service.start(process, baseState()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");

        release.countDown();
        awaitUntil(() -> !service.isRunning("proc-1"));
    }

    @Test
    void start_rekickAfterTerminal_isFree() {
        doAnswer(inv -> {
                    HactarState s = inv.getArgument(0);
                    s.setExecutionResult(1);
                    s.setExecutionDurationMs(1);
                    return HactarStatus.DONE;
                })
                .when(executingPhase)
                .execute(any(), any());
        doAnswer(inv -> HactarStatus.EXECUTING).when(loadingPhase).execute(any(), any());

        service.start(process, baseState());
        awaitUntil(() -> !service.isRunning("proc-1"));

        // Second kick after terminal: no refusal, fresh state.
        service.start(process, baseState());
        awaitUntil(() -> !service.isRunning("proc-1"));
        assertThat(historyNotes())
                .filteredOn(n -> n.getContent() != null && n.getContent().contains("run started"))
                .hasSize(2);
        assertThat(pendingNotes()).noneMatch(n -> n != null && n.contains("run started"));
    }

    // ──────────────────── Stop ────────────────────

    @Test
    void stop_midScript_relabelsCancelledAsStopReason() throws Exception {
        doAnswer(inv -> HactarStatus.EXECUTING).when(loadingPhase).execute(any(), any());
        // Simulates what the real GraaljsScriptExecutor does on interrupt:
        // CANCELLED ScriptExecutionException -> the phase records the error
        // class and returns FAILED.
        CountDownLatch enteredExecute = new CountDownLatch(1);
        doAnswer(inv -> {
                    enteredExecute.countDown();
                    try {
                        Thread.sleep(10_000);
                        return HactarStatus.DONE;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        HactarState s = inv.getArgument(0);
                        s.setExecutionErrorClass("CANCELLED");
                        s.setFailureReason("Script execution failed (CANCELLED): Script execution interrupted");
                        return HactarStatus.FAILED;
                    }
                })
                .when(executingPhase)
                .execute(any(), any());

        service.start(process, baseState());
        // Deterministic: wait until the runner is INSIDE the (blocked) phase.
        enteredExecute.await();

        boolean stopped = service.stop("proc-1");
        assertThat(stopped).isTrue();
        awaitUntil(() -> !service.isRunning("proc-1"));

        HactarState state = loadedState();
        assertThat(state.getStatus()).isEqualTo(HactarStatus.FAILED);
        // Relabeled as a stop, not reported as a script defect:
        assertThat(state.getFailureReason()).contains("stopped by request");
        assertThat(state.getFailureReason()).contains("cancelled mid-run");
        assertThat(pendingNotes()).anyMatch(n -> n != null && n.contains("stopped by request"));
    }

    @Test
    void stop_whilePhaseCompletesAnyway_keepsTruthfulDone() throws Exception {
        doAnswer(inv -> HactarStatus.EXECUTING).when(loadingPhase).execute(any(), any());
        CountDownLatch enteredExecute = new CountDownLatch(1);
        // Never counted down: the stop's interrupt is the only release.
        CountDownLatch blockedInPhase = new CountDownLatch(1);
        doAnswer(inv -> {
                    enteredExecute.countDown();
                    try {
                        blockedInPhase.await();
                        return HactarStatus.DONE; // unreachable — interrupt releases first
                    } catch (InterruptedException e) {
                        // The stop woke the phase; it had already produced
                        // its result — completing anyway, DONE survives.
                        HactarState s = inv.getArgument(0);
                        s.setExecutionResult("done anyway");
                        s.setExecutionDurationMs(3);
                        return HactarStatus.DONE;
                    }
                })
                .when(executingPhase)
                .execute(any(), any());

        service.start(process, baseState());
        // Deterministic: wait until the runner is INSIDE the phase.
        enteredExecute.await();

        // The stop interrupts the completing phase; the answer swallows the
        // interrupt and returns DONE anyway — the run keeps its truthful DONE
        // (only the CANCELLED path is relabeled as a stop).
        assertThat(service.stop("proc-1")).isTrue();
        awaitUntil(() -> !service.isRunning("proc-1"));

        HactarState state = loadedState();
        assertThat(state.getStatus()).isEqualTo(HactarStatus.DONE);
        assertThat(pendingNotes()).anyMatch(n -> n != null && n.contains("run finished"));
    }

    @Test
    void stop_withoutLiveRun_isANoop() {
        assertThat(service.stop("proc-1")).isFalse();
    }

    // ──────────────────── Orphan handling ────────────────────

    @Test
    void failOrphanedRun_marksMidRunStateTerminal() {
        HactarState state = baseState();
        state.setStatus(HactarStatus.EXECUTING);

        HactarState out = service.failOrphanedRun(process, state);

        assertThat(out.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(out.getFailureReason()).contains("engine restart");
    }

    // ──────────────────── Progress ring (F3) ────────────────────

    @Test
    void progressRing_recordsCappedTail() {
        for (int i = 0; i < 30; i++) {
            progressRing.record("proc-1", "note " + i, Map.of("i", i));
        }
        List<HactarProgressRing.Entry> tail = progressRing.tail("proc-1", 5);
        assertThat(tail).hasSize(5);
        assertThat(tail.get(4).text()).contains("note 29");
        // cap at 20 entries overall
        assertThat(progressRing.tail("proc-1", 100)).hasSize(20);
        progressRing.clear("proc-1");
        assertThat(progressRing.tail("proc-1", 100)).isEmpty();
    }
}
