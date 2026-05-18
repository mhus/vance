package de.mhus.vance.brain.deepthought;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.deepthought.DeepThoughtState;
import de.mhus.vance.api.deepthought.DeepThoughtStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase-0 lifecycle test — drives the {@link DeepThoughtEngine} state
 * machine through the four-phase stub pipeline
 * (READY → DRAFTING → VALIDATING → PERSISTING → DONE) and pins the
 * idempotency contract for terminal states.
 *
 * <p>No LLM, no document I/O — phase methods are still stubs at this
 * point. Real DRAFTING/VALIDATING/PERSISTING tests land in task #47
 * once the phases are implemented.
 */
class DeepThoughtEngineLifecycleTest {

    private ThinkProcessService thinkProcessService;
    private ProcessEventEmitter eventEmitter;
    private ObjectMapper objectMapper;
    private ThinkEngineContext ctx;

    private DeepThoughtEngine engine;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        eventEmitter = mock(ProcessEventEmitter.class);
        objectMapper = JsonMapper.builder().build();
        ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.<SteerMessage>of());

        doAnswer(inv -> null).when(thinkProcessService)
                .replaceEngineParams(anyString(), any());

        engine = new DeepThoughtEngine(thinkProcessService, eventEmitter, objectMapper);
    }

    // ──────────────────── Happy path ────────────────────

    @Test
    void start_persistsInitialStateAsReady_andSchedulesTurn() {
        ThinkProcessDocument process = newProcess();

        engine.start(process, ctx);

        DeepThoughtState state = readState(process);
        assertThat(state.getStatus()).isEqualTo(DeepThoughtStatus.READY);
        assertThat(state.getGoal()).isEqualTo("write me a hello-script");
        assertThat(state.getTargetName()).isEqualTo("greet.js");
        assertThat(state.getMaxRecoveries()).isEqualTo(5);
        assertThat(state.isExecuteOnDone()).isFalse();
        verify(eventEmitter).scheduleTurn(process.getId());
    }

    @Test
    void runTurns_advanceReadyToDoneThroughAllStubPhases() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);

        int turns = drainTurns(process, 10);

        DeepThoughtState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
        assertThat(finalState.getGeneratedCode()).isNotNull();
        assertThat(finalState.getPersistedPath()).isEqualTo("scripts/greet.js");
        assertThat(finalState.getValidationErrors()).isEmpty();

        // READY→DRAFTING→VALIDATING→PERSISTING→DONE = 4 advancing
        // turns, plus the terminal turn that observes DONE and closes
        // the process.
        assertThat(turns).isEqualTo(4);

        verify(thinkProcessService, atLeastOnce())
                .closeProcess(eq(process.getId()), eq(CloseReason.DONE));
    }

    @Test
    void runTurns_withExecuteOnDone_passThroughExecutingPhase() {
        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(DeepThoughtEngine.EXECUTE_ON_DONE_KEY, true);
        engine.start(process, ctx);

        drainTurns(process, 10);

        DeepThoughtState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
        // EXECUTING was visited as an intermediate stub-phase that
        // jumps straight to DONE; persistedPath is still set.
        assertThat(finalState.getPersistedPath()).isEqualTo("scripts/greet.js");
    }

    // ──────────────────── Idempotency ────────────────────

    @Test
    void terminalDoneStateIsIdempotentAcrossExtraTurns() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        drainTurns(process, 10);

        DeepThoughtState before = readState(process);
        engine.runTurn(process, ctx);
        engine.runTurn(process, ctx);
        DeepThoughtState after = readState(process);

        assertThat(after.getStatus()).isEqualTo(DeepThoughtStatus.DONE);
        assertThat(after.getPersistedPath()).isEqualTo(before.getPersistedPath());
        // generatedCode must not be re-drafted in the terminal state.
        assertThat(after.getGeneratedCode()).isEqualTo(before.getGeneratedCode());
    }

    @Test
    void terminalFailedStateIsIdempotent() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        // Hand-craft a FAILED state in storage.
        DeepThoughtState s = readState(process);
        s.setStatus(DeepThoughtStatus.FAILED);
        s.setFailureReason("test-induced");
        engine.persistState(process, s);
        // Reset the emitter mock so we can pin "no NEW schedule
        // calls happen during the FAILED short-circuit" — start()
        // already scheduled one turn which is expected.
        reset(eventEmitter);

        engine.runTurn(process, ctx);
        engine.runTurn(process, ctx);

        DeepThoughtState after = readState(process);
        assertThat(after.getStatus()).isEqualTo(DeepThoughtStatus.FAILED);
        verify(thinkProcessService, atLeastOnce())
                .closeProcess(eq(process.getId()), eq(CloseReason.STALE));
        // FAILED short-circuit must not schedule another turn.
        verify(eventEmitter, times(0)).scheduleTurn(anyString());
    }

    // ──────────────────── Input validation ────────────────────

    @Test
    void start_withoutGoal_throws() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("proc-deep-2");
        process.setTenantId("acme");
        process.setProjectId("p");
        process.setSessionId("s");
        process.setEngineParams(new LinkedHashMap<>());
        // process.goal stays null — engineParams.goal too.

        assertThatThrownBy(() -> engine.start(process, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a goal");
    }

    @Test
    void start_normalizesTargetName_appendsJsSuffix() {
        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(DeepThoughtEngine.TARGET_NAME_KEY, "compute");

        engine.start(process, ctx);

        DeepThoughtState state = readState(process);
        assertThat(state.getTargetName()).isEqualTo("compute.js");
    }

    @Test
    void start_defaultsTargetName_whenAbsent() {
        ThinkProcessDocument process = newProcess();
        process.getEngineParams().remove(DeepThoughtEngine.TARGET_NAME_KEY);

        engine.start(process, ctx);

        DeepThoughtState state = readState(process);
        assertThat(state.getTargetName()).isEqualTo("generated.js");
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Test
    void summarizeForParent_onDone_carriesPersistedPath() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        drainTurns(process, 10);

        ParentReport report = engine.summarizeForParent(process, ProcessEventType.DONE);

        assertThat(report.humanSummary()).contains("scripts/greet.js");
        assertThat(report.payload())
                .containsEntry("status", "DONE")
                .containsEntry("persistedPath", "scripts/greet.js");
    }

    @Test
    void summarizeForParent_onFailed_carriesFailureReason() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        DeepThoughtState s = readState(process);
        s.setStatus(DeepThoughtStatus.FAILED);
        s.setFailureReason("LLM returned 451");
        engine.persistState(process, s);

        ParentReport report = engine.summarizeForParent(process, ProcessEventType.FAILED);

        assertThat(report.humanSummary()).contains("LLM returned 451");
        assertThat(report.payload()).containsEntry("status", "FAILED");
    }

    // ──────────────────── Helpers ────────────────────

    private int drainTurns(ThinkProcessDocument process, int cap) {
        for (int i = 0; i < cap; i++) {
            DeepThoughtState before = readState(process);
            if (isTerminal(before.getStatus())) {
                // The turn that observes terminal state is what closes
                // the process — invoke once and stop counting.
                engine.runTurn(process, ctx);
                return i;
            }
            engine.runTurn(process, ctx);
        }
        throw new AssertionError("turn cap exceeded — possible infinite loop");
    }

    private static boolean isTerminal(DeepThoughtStatus s) {
        return s == DeepThoughtStatus.DONE || s == DeepThoughtStatus.FAILED;
    }

    private DeepThoughtState readState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(DeepThoughtEngine.STATE_KEY);
        if (raw == null) return DeepThoughtState.builder().build();
        return objectMapper.convertValue(raw, DeepThoughtState.class);
    }

    private static ThinkProcessDocument newProcess() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(DeepThoughtEngine.TARGET_NAME_KEY, "greet.js");
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-deep-1");
        p.setTenantId("acme");
        p.setProjectId("test-project");
        p.setSessionId("sess-1");
        p.setGoal("write me a hello-script");
        p.setEngineParams(params);
        return p;
    }
}
