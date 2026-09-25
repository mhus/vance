package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.hactar.phases.ExecutingPhase;
import de.mhus.vance.brain.hactar.phases.LoadingPhase;
import de.mhus.vance.brain.hactar.phases.ValidatingPhase;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lifecycle tests for Hactar v2 (pure executor). Verifies the
 * READY → LOADING → [VALIDATING] → EXECUTING → DONE state machine,
 * buildInitialState input validation, and dispatch routing. Phase
 * internals (HactarService.validate, ScriptExecutor) are mocked
 * via the constituent Phase beans.
 */
class HactarEngineLifecycleTest {

    private ThinkProcessService thinkProcessService;
    private ProcessEventEmitter eventEmitter;
    private LoadingPhase loadingPhase;
    private ValidatingPhase validatingPhase;
    private ExecutingPhase executingPhase;
    private HactarStateStore stateStore;
    private HactarRunService runService;
    private HactarSessionLoop sessionLoop;
    private HactarEngine engine;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        eventEmitter = mock(ProcessEventEmitter.class);
        loadingPhase = mock(LoadingPhase.class);
        validatingPhase = mock(ValidatingPhase.class);
        executingPhase = mock(ExecutingPhase.class);
        stateStore =
                new HactarStateStore(thinkProcessService, JsonMapper.builder().build());
        runService = mock(HactarRunService.class);
        sessionLoop = mock(HactarSessionLoop.class);
        engine = new HactarEngine(
                thinkProcessService,
                eventEmitter,
                loadingPhase,
                validatingPhase,
                executingPhase,
                stateStore,
                runService,
                sessionLoop,
                mock(de.mhus.vance.shared.chat.ChatMessageService.class),
                JsonMapper.builder().build());

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setSessionId("sess-1");
        process.setProjectId("proj-1");
        ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.of());
    }

    // ──────────────────── Metadata ────────────────────

    @Test
    void engineName_isHactarV2() {
        assertThat(engine.name()).isEqualTo("hactar");
        assertThat(engine.version()).startsWith("2.");
        assertThat(engine.allowedTools()).isEmpty();
    }

    // ──────────────────── buildInitialState ────────────────────

    @Test
    void buildInitialState_requiresScriptRef() {
        process.setEngineParams(new LinkedHashMap<>());

        assertThatThrownBy(() -> engine.buildInitialState(process))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scriptRef");
    }

    @Test
    void buildInitialState_rejectsNonJsLanguage() {
        process.setEngineParams(Map.of(
                HactarEngine.SCRIPT_REF_KEY, "scripts/x.py",
                HactarEngine.LANGUAGE_KEY, "py"));

        assertThatThrownBy(() -> engine.buildInitialState(process))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only language='js'");
    }

    @Test
    void buildInitialState_defaultsLanguageAndValidateBeforeRun() {
        process.setEngineParams(Map.of(HactarEngine.SCRIPT_REF_KEY, "scripts/mailbot.js"));

        HactarState state = engine.buildInitialState(process);

        assertThat(state.getScriptRef()).isEqualTo("scripts/mailbot.js");
        assertThat(state.getLanguage()).isEqualTo("js");
        assertThat(state.isValidateBeforeRun()).isFalse();
        assertThat(state.getStatus()).isEqualTo(HactarStatus.READY);
    }

    @Test
    void buildInitialState_readsValidateBeforeRunTrue() {
        process.setEngineParams(
                Map.of(HactarEngine.SCRIPT_REF_KEY, "scripts/mailbot.js", HactarEngine.VALIDATE_BEFORE_RUN_KEY, true));

        HactarState state = engine.buildInitialState(process);

        assertThat(state.isValidateBeforeRun()).isTrue();
    }

    // ──────────────────── Dispatch ────────────────────

    @Test
    void runTurn_readyTransitionsToLoading_thenSchedulesNextTurn() {
        seedState(HactarState.builder()
                .status(HactarStatus.READY)
                .scriptRef("scripts/x.js")
                .build());

        engine.runTurn(process, ctx);

        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.LOADING);
        org.mockito.Mockito.verify(eventEmitter).scheduleTurn("proc-1");
        org.mockito.Mockito.verify(thinkProcessService).updateStatus("proc-1", ThinkProcessStatus.IDLE);
    }

    @Test
    void runTurn_loadingDelegatesToLoadingPhase() {
        seedState(HactarState.builder()
                .status(HactarStatus.LOADING)
                .scriptRef("scripts/x.js")
                .build());
        when(loadingPhase.execute(any(), any())).thenReturn(HactarStatus.EXECUTING);

        engine.runTurn(process, ctx);

        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.EXECUTING);
        org.mockito.Mockito.verify(loadingPhase).execute(any(), eq(process));
    }

    @Test
    void runTurn_validatingDelegatesToValidatingPhase() {
        seedState(HactarState.builder()
                .status(HactarStatus.VALIDATING)
                .scriptRef("scripts/x.js")
                .scriptBody("var x = 1;")
                .validateBeforeRun(true)
                .build());
        when(validatingPhase.execute(any(), any())).thenReturn(HactarStatus.EXECUTING);

        engine.runTurn(process, ctx);

        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.EXECUTING);
        org.mockito.Mockito.verify(validatingPhase).execute(any(), eq(process));
    }

    @Test
    void runTurn_executingDelegatesToExecutingPhase_thenClosesOnDone() {
        seedState(HactarState.builder()
                .status(HactarStatus.EXECUTING)
                .scriptRef("scripts/x.js")
                .scriptBody("var x = 1;")
                .build());
        when(executingPhase.execute(any(), any())).thenReturn(HactarStatus.DONE);

        engine.runTurn(process, ctx);

        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.DONE);
        org.mockito.Mockito.verify(thinkProcessService).closeProcess("proc-1", CloseReason.DONE);
        org.mockito.Mockito.verify(eventEmitter, org.mockito.Mockito.never()).scheduleTurn(any());
    }

    @Test
    void runTurn_failedClosesProcess() {
        seedState(HactarState.builder()
                .status(HactarStatus.LOADING)
                .scriptRef("scripts/missing.js")
                .build());
        when(loadingPhase.execute(any(), any())).thenAnswer(invocation -> {
            HactarState s = invocation.getArgument(0);
            s.setFailureReason("Script document not found");
            return HactarStatus.FAILED;
        });

        engine.runTurn(process, ctx);

        HactarState saved = loadedState();
        assertThat(saved.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(saved.getFailureReason()).contains("not found");
        org.mockito.Mockito.verify(thinkProcessService).closeProcess("proc-1", CloseReason.STALE);
    }

    @Test
    void runTurn_doneStatusShortCircuitsToClose() {
        // Defensive: terminal status must not re-fire phases.
        seedState(HactarState.builder()
                .status(HactarStatus.DONE)
                .scriptRef("scripts/x.js")
                .build());

        engine.runTurn(process, ctx);

        org.mockito.Mockito.verify(thinkProcessService).closeProcess("proc-1", CloseReason.DONE);
        org.mockito.Mockito.verifyNoInteractions(loadingPhase, validatingPhase, executingPhase);
    }

    @Test
    void runTurn_runtimeExceptionMarksFailedAndCloses() {
        seedState(HactarState.builder()
                .status(HactarStatus.LOADING)
                .scriptRef("scripts/x.js")
                .build());
        when(loadingPhase.execute(any(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> engine.runTurn(process, ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        HactarState saved = loadedState();
        assertThat(saved.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(saved.getFailureReason()).contains("runTurn threw").contains("boom");
        org.mockito.Mockito.verify(thinkProcessService).closeProcess("proc-1", CloseReason.STALE);
    }

    // ──────────────────── Session mode ────────────────────

    @Test
    void start_sessionChatForm_persistsIdentityAndWaits() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, true)));

        engine.start(process, ctx);

        HactarState state = loadedState();
        assertThat(state.isChatIdentity()).isTrue();
        assertThat(state.getStatus()).isEqualTo(HactarStatus.READY);
        assertThat(state.getScriptRef()).isNull();
        // No auto-kick, no scheduled turn — the identity waits (lazy).
        org.mockito.Mockito.verifyNoInteractions(runService);
        org.mockito.Mockito.verify(eventEmitter, org.mockito.Mockito.never()).scheduleTurn(any());
    }

    @Test
    void start_sessionWorkerForm_autoKicksRun() {
        process.setEngineParams(new LinkedHashMap<>(
                Map.of(HactarEngine.SESSION_MODE_KEY, true, HactarEngine.SCRIPT_REF_KEY, "scripts/x.js")));

        engine.start(process, ctx);

        HactarState state = loadedState();
        assertThat(state.isChatIdentity()).isFalse();
        assertThat(state.getScriptRef()).isEqualTo("scripts/x.js");
        org.mockito.Mockito.verify(runService).start(org.mockito.ArgumentMatchers.eq(process), any());
    }

    @Test
    void runTurn_sessionWorkerFormTerminal_emitsReplyAndCloses() {
        process.setEngineParams(new LinkedHashMap<>(
                Map.of(HactarEngine.SESSION_MODE_KEY, true, HactarEngine.SCRIPT_REF_KEY, "scripts/x.js")));
        process.setParentProcessId("parent-1");
        seedState(HactarState.builder()
                .status(HactarStatus.DONE)
                .scriptRef("scripts/x.js")
                .chatIdentity(false)
                .executionResult(Map.of("done", true))
                .executionDurationMs(7)
                .build());
        when(ctx.drainPending()).thenReturn(List.of());

        engine.runTurn(process, ctx);

        // Worker form at terminal: reply + close, no agent turn.
        org.mockito.Mockito.verify(ctx).emitReply(any(), any(), any());
        org.mockito.Mockito.verify(thinkProcessService).closeProcess("proc-1", CloseReason.DONE);
        org.mockito.Mockito.verifyNoInteractions(sessionLoop);
    }

    @Test
    void runTurn_sessionChatFormTerminal_goesToAgentLoop() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, true)));
        seedState(HactarState.builder()
                .status(HactarStatus.DONE)
                .scriptRef("scripts/x.js")
                .chatIdentity(true)
                .build());
        when(ctx.drainPending()).thenReturn(List.of()).thenReturn(List.of());

        engine.runTurn(process, ctx);

        // Chat form: the terminal wakeup drives the agent report turn — the
        // process stays open (re-arm, F1), no closeProcess.
        org.mockito.Mockito.verify(thinkProcessService, org.mockito.Mockito.never())
                .closeProcess(any(), any());
    }

    @Test
    void runTurn_sessionMode_haltRequested_yieldsWithoutDraining() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, true)));
        seedState(HactarState.builder()
                .status(HactarStatus.DONE)
                .scriptRef("scripts/x.js")
                .chatIdentity(true)
                .build());
        // Pause contract (Arthur parity): the halt flag goes out before the
        // pause lane task, so a drain-loop still holding the lane must bail
        // BEFORE consuming the inbox — queued user messages stay queued for
        // the resume turn instead of being eaten by a parked turn that never
        // answers.
        when(thinkProcessService.isHaltRequested("proc-1")).thenReturn(true);

        engine.runTurn(process, ctx);

        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).drainPending();
        org.mockito.Mockito.verifyNoInteractions(sessionLoop);
    }

    @Test
    void stop_sessionMode_cancelsLiveRunBeforeClose() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, true)));

        engine.stop(process, ctx);

        org.mockito.Mockito.verify(runService).stop("proc-1");
        org.mockito.Mockito.verify(thinkProcessService).closeProcess("proc-1", CloseReason.STOPPED);
    }

    @Test
    void resume_sessionModeOrphanedMidRun_marksFailed() {
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, true)));
        seedState(HactarState.builder()
                .status(HactarStatus.EXECUTING)
                .scriptRef("scripts/x.js")
                .chatIdentity(true)
                .build());
        when(runService.isRunning("proc-1")).thenReturn(false);

        engine.resume(process, ctx);

        // The engine DETECTS the orphan (mid-run state, no live handle) and
        // hands it to the run service — the mocked service records the call;
        // the persistence itself is covered by HactarRunServiceTest.
        org.mockito.Mockito.verify(runService)
                .failOrphanedRun(org.mockito.ArgumentMatchers.eq(process), any(HactarState.class));
    }

    @Test
    void sessionMode_paramParsingAcceptsStringAndBoolean() {
        assertThat(HactarEngine.sessionMode(process)).isFalse();
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, "true")));
        assertThat(HactarEngine.sessionMode(process)).isTrue();
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, true)));
        assertThat(HactarEngine.sessionMode(process)).isTrue();
        process.setEngineParams(new LinkedHashMap<>(Map.of(HactarEngine.SESSION_MODE_KEY, "nope")));
        assertThat(HactarEngine.sessionMode(process)).isFalse();
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private void seedState(HactarState state) {
        // Merge into the existing engine params — the session tests set
        // sessionMode/scriptRef params BEFORE seeding, and a fresh map
        // would wipe them (the engine then routes to the headless path).
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(process.getEngineParams());
        Map<String, Object> serialized = JsonMapper.builder().build().convertValue(state, Map.class);
        p.put(HactarEngine.STATE_KEY, serialized);
        process.setEngineParams(p);
    }

    private HactarState loadedState() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(thinkProcessService, org.mockito.Mockito.atLeastOnce())
                .replaceEngineParams(eq("proc-1"), captor.capture());
        Map<String, Object> latest =
                captor.getAllValues().get(captor.getAllValues().size() - 1);
        Object raw = latest.get(HactarEngine.STATE_KEY);
        return JsonMapper.builder().build().convertValue(raw, HactarState.class);
    }
}
