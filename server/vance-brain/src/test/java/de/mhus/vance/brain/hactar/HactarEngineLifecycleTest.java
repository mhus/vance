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
        engine = new HactarEngine(
                thinkProcessService,
                eventEmitter,
                JsonMapper.builder().build(),
                loadingPhase,
                validatingPhase,
                executingPhase);

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
        process.setEngineParams(Map.of(
                HactarEngine.SCRIPT_REF_KEY, "scripts/mailbot.js"));

        HactarState state = engine.buildInitialState(process);

        assertThat(state.getScriptRef()).isEqualTo("scripts/mailbot.js");
        assertThat(state.getLanguage()).isEqualTo("js");
        assertThat(state.isValidateBeforeRun()).isFalse();
        assertThat(state.getStatus()).isEqualTo(HactarStatus.READY);
    }

    @Test
    void buildInitialState_readsValidateBeforeRunTrue() {
        process.setEngineParams(Map.of(
                HactarEngine.SCRIPT_REF_KEY, "scripts/mailbot.js",
                HactarEngine.VALIDATE_BEFORE_RUN_KEY, true));

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
        org.mockito.Mockito.verify(thinkProcessService)
                .updateStatus("proc-1", ThinkProcessStatus.IDLE);
    }

    @Test
    void runTurn_loadingDelegatesToLoadingPhase() {
        seedState(HactarState.builder()
                .status(HactarStatus.LOADING)
                .scriptRef("scripts/x.js")
                .build());
        when(loadingPhase.execute(any(), any(), any()))
                .thenReturn(HactarStatus.EXECUTING);

        engine.runTurn(process, ctx);

        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.EXECUTING);
        org.mockito.Mockito.verify(loadingPhase).execute(any(), eq(process), eq(ctx));
    }

    @Test
    void runTurn_validatingDelegatesToValidatingPhase() {
        seedState(HactarState.builder()
                .status(HactarStatus.VALIDATING)
                .scriptRef("scripts/x.js")
                .scriptBody("var x = 1;")
                .validateBeforeRun(true)
                .build());
        when(validatingPhase.execute(any(), any(), any()))
                .thenReturn(HactarStatus.EXECUTING);

        engine.runTurn(process, ctx);

        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.EXECUTING);
        org.mockito.Mockito.verify(validatingPhase).execute(any(), eq(process), eq(ctx));
    }

    @Test
    void runTurn_executingDelegatesToExecutingPhase_thenClosesOnDone() {
        seedState(HactarState.builder()
                .status(HactarStatus.EXECUTING)
                .scriptRef("scripts/x.js")
                .scriptBody("var x = 1;")
                .build());
        when(executingPhase.execute(any(), any(), any()))
                .thenReturn(HactarStatus.DONE);

        engine.runTurn(process, ctx);

        assertThat(loadedState().getStatus()).isEqualTo(HactarStatus.DONE);
        org.mockito.Mockito.verify(thinkProcessService)
                .closeProcess("proc-1", CloseReason.DONE);
        org.mockito.Mockito.verify(eventEmitter, org.mockito.Mockito.never())
                .scheduleTurn(any());
    }

    @Test
    void runTurn_failedClosesProcess() {
        seedState(HactarState.builder()
                .status(HactarStatus.LOADING)
                .scriptRef("scripts/missing.js")
                .build());
        when(loadingPhase.execute(any(), any(), any()))
                .thenAnswer(invocation -> {
                    HactarState s = invocation.getArgument(0);
                    s.setFailureReason("Script document not found");
                    return HactarStatus.FAILED;
                });

        engine.runTurn(process, ctx);

        HactarState saved = loadedState();
        assertThat(saved.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(saved.getFailureReason()).contains("not found");
        org.mockito.Mockito.verify(thinkProcessService)
                .closeProcess("proc-1", CloseReason.STALE);
    }

    @Test
    void runTurn_doneStatusShortCircuitsToClose() {
        // Defensive: terminal status must not re-fire phases.
        seedState(HactarState.builder()
                .status(HactarStatus.DONE)
                .scriptRef("scripts/x.js")
                .build());

        engine.runTurn(process, ctx);

        org.mockito.Mockito.verify(thinkProcessService)
                .closeProcess("proc-1", CloseReason.DONE);
        org.mockito.Mockito.verifyNoInteractions(loadingPhase, validatingPhase, executingPhase);
    }

    @Test
    void runTurn_runtimeExceptionMarksFailedAndCloses() {
        seedState(HactarState.builder()
                .status(HactarStatus.LOADING)
                .scriptRef("scripts/x.js")
                .build());
        when(loadingPhase.execute(any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> engine.runTurn(process, ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        HactarState saved = loadedState();
        assertThat(saved.getStatus()).isEqualTo(HactarStatus.FAILED);
        assertThat(saved.getFailureReason()).contains("runTurn threw").contains("boom");
        org.mockito.Mockito.verify(thinkProcessService)
                .closeProcess("proc-1", CloseReason.STALE);
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private void seedState(HactarState state) {
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, Object> serialized = JsonMapper.builder().build()
                .convertValue(state, Map.class);
        p.put(HactarEngine.STATE_KEY, serialized);
        process.setEngineParams(p);
    }

    private HactarState loadedState() {
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(thinkProcessService, org.mockito.Mockito.atLeastOnce())
                .replaceEngineParams(eq("proc-1"), captor.capture());
        Map<String, Object> latest = captor.getAllValues().get(
                captor.getAllValues().size() - 1);
        Object raw = latest.get(HactarEngine.STATE_KEY);
        return JsonMapper.builder().build().convertValue(raw, HactarState.class);
    }
}
