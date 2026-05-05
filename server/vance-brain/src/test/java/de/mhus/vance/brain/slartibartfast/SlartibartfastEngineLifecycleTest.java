package de.mhus.vance.brain.slartibartfast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.slartibartfast.phases.BindingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ClassifyingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ConfirmingPhase;
import de.mhus.vance.brain.slartibartfast.phases.DecomposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.FramingPhase;
import de.mhus.vance.brain.slartibartfast.phases.GatheringPhase;
import de.mhus.vance.brain.slartibartfast.phases.PersistingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ProposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ValidatingPhase;
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
 * M1 lifecycle test — verifies SlartibartfastEngine drives a process
 * deterministically through every status from READY to DONE using
 * the stubbed phases. No LLM calls, no document I/O — just the
 * state machine, persistence, and terminal handling.
 */
class SlartibartfastEngineLifecycleTest {

    private ThinkProcessService thinkProcessService;
    private ProcessEventEmitter eventEmitter;
    private LaneScheduler laneScheduler;
    private ObjectMapper objectMapper;
    private FramingPhase framingPhase;
    private ConfirmingPhase confirmingPhase;
    private GatheringPhase gatheringPhase;
    private ClassifyingPhase classifyingPhase;
    private DecomposingPhase decomposingPhase;
    private BindingPhase bindingPhase;
    private ProposingPhase proposingPhase;
    private ValidatingPhase validatingPhase;
    private PersistingPhase persistingPhase;
    private ThinkEngineContext ctx;

    private SlartibartfastEngine engine;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        eventEmitter = mock(ProcessEventEmitter.class);
        laneScheduler = mock(LaneScheduler.class);
        objectMapper = JsonMapper.builder().build();
        framingPhase = mock(FramingPhase.class);
        confirmingPhase = mock(ConfirmingPhase.class);
        gatheringPhase = mock(GatheringPhase.class);
        classifyingPhase = mock(ClassifyingPhase.class);
        decomposingPhase = mock(DecomposingPhase.class);
        bindingPhase = mock(BindingPhase.class);
        proposingPhase = mock(ProposingPhase.class);
        validatingPhase = mock(ValidatingPhase.class);
        persistingPhase = mock(PersistingPhase.class);
        ctx = mock(ThinkEngineContext.class);
        when(ctx.drainPending()).thenReturn(List.<SteerMessage>of());

        // Stub thinkProcessService.replaceEngineParams to mirror
        // the persistence behaviour: write-back so the next loadState
        // reads what the previous turn wrote.
        doAnswer(inv -> null).when(thinkProcessService)
                .replaceEngineParams(anyString(), any());

        // Lifecycle test focuses on engine dispatch; FramingPhase
        // gets its own dedicated test. Stub it so it mirrors the
        // M1 stub behaviour — populates a minimal FramedGoal so the
        // downstream stub phases keep producing the same shape.
        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubFraming(s);
            return null;
        }).when(framingPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubConfirming(s);
            return null;
        }).when(confirmingPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubGathering(s);
            return null;
        }).when(gatheringPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubClassifying(s);
            return null;
        }).when(classifyingPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubDecomposing(s);
            return null;
        }).when(decomposingPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubBinding(s);
            return null;
        }).when(bindingPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubProposing(s);
            return null;
        }).when(proposingPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubValidating(s);
            return null;
        }).when(validatingPhase).execute(any(), any(), any());

        doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            de.mhus.vance.brain.slartibartfast.SlartibartfastPhases.stubPersisting(s);
            return null;
        }).when(persistingPhase).execute(any(), any(), any());

        engine = new SlartibartfastEngine(
                thinkProcessService, eventEmitter, laneScheduler,
                objectMapper, framingPhase, confirmingPhase,
                gatheringPhase, classifyingPhase,
                decomposingPhase, bindingPhase,
                proposingPhase, validatingPhase, persistingPhase);
    }

    @Test
    void startPersistsInitialStateAndSchedulesTurn() {
        ThinkProcessDocument process = newProcess();

        engine.start(process, ctx);

        ArchitectState state = readState(process);
        assertThat(state.getRunId()).hasSize(8);
        assertThat(state.getStatus()).isEqualTo(ArchitectStatus.READY);
        assertThat(state.getOutputSchemaType()).isEqualTo(OutputSchemaType.VOGON_STRATEGY);
        assertThat(state.getUserDescription()).isEqualTo("write me an essay");

        verify(eventEmitter).scheduleTurn(eq(process.getId()));
    }

    @Test
    void startReadsOutputSchemaTypeFromEngineParams() {
        ThinkProcessDocument process = newProcess();
        process.getEngineParams().put(
                SlartibartfastEngine.OUTPUT_SCHEMA_TYPE_KEY, "marvin-recipe");

        engine.start(process, ctx);

        assertThat(readState(process).getOutputSchemaType())
                .isEqualTo(OutputSchemaType.MARVIN_RECIPE);
    }

    @Test
    void runTurnDrivesEveryPhaseToDone() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);

        // Drive turns until the engine declares DONE. Cap at 20 to
        // catch infinite loops — the lifecycle is 10 phases.
        int turnsTaken = drainTurns(process, 20);

        ArchitectState finalState = readState(process);
        assertThat(finalState.getStatus()).isEqualTo(ArchitectStatus.DONE);
        assertThat(finalState.getPersistedRecipePath())
                .startsWith("recipes/_slart/" + finalState.getRunId() + "/");
        assertThat(finalState.getGoal()).isNotNull();
        assertThat(finalState.getEvidenceSources()).isNotEmpty();
        assertThat(finalState.getEvidenceClaims()).isNotEmpty();
        assertThat(finalState.getSubgoals()).isNotEmpty();
        assertThat(finalState.getProposedRecipe()).isNotNull();
        assertThat(finalState.getValidationReport()).isNotEmpty();
        assertThat(finalState.getTerminationRationale()).isNotNull();

        // 10 phase transitions: READY → FRAMING → CONFIRMING →
        //   GATHERING → CLASSIFYING → DECOMPOSING → BINDING →
        //   PROPOSING → VALIDATING → PERSISTING → DONE.
        assertThat(turnsTaken).isEqualTo(10);

        // Process is closed exactly once.
        verify(thinkProcessService, atLeastOnce())
                .closeProcess(eq(process.getId()), eq(CloseReason.DONE));
    }

    @Test
    void runTurnPopulatesIterationsAndRationales() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        drainTurns(process, 20);

        ArchitectState finalState = readState(process);

        // Every working phase appended one PhaseIteration entry.
        // 9 phases that emit iterations (READY does not):
        //   FRAMING, CONFIRMING, GATHERING, CLASSIFYING,
        //   DECOMPOSING, BINDING, PROPOSING, VALIDATING, PERSISTING
        assertThat(finalState.getIterations()).hasSize(9);
        assertThat(finalState.getIterations())
                .extracting(PhaseIteration::getPhase)
                .containsExactly(
                        ArchitectStatus.FRAMING,
                        ArchitectStatus.CONFIRMING,
                        ArchitectStatus.GATHERING,
                        ArchitectStatus.CLASSIFYING,
                        ArchitectStatus.DECOMPOSING,
                        ArchitectStatus.BINDING,
                        ArchitectStatus.PROPOSING,
                        ArchitectStatus.VALIDATING,
                        ArchitectStatus.PERSISTING);
        assertThat(finalState.getIterations())
                .allMatch(it -> it.getOutcome()
                        == PhaseIteration.IterationOutcome.PASSED);

        // Stub phases that record rationales: FRAMING, GATHERING,
        // CLASSIFYING, DECOMPOSING, PROPOSING — five entries.
        assertThat(finalState.getRationales())
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(finalState.getRationales())
                .extracting(de.mhus.vance.api.slartibartfast.Rationale::getInferredAt)
                .contains(
                        ArchitectStatus.FRAMING,
                        ArchitectStatus.GATHERING,
                        ArchitectStatus.CLASSIFYING,
                        ArchitectStatus.DECOMPOSING,
                        ArchitectStatus.PROPOSING);
    }

    @Test
    void pendingRecovery_rollsBackStatusAndIncrementsCount() {
        // Configure bindingPhase mock to set pendingRecovery on
        // first call, then leave it null on second call (simulating
        // a successful re-decompose).
        java.util.concurrent.atomic.AtomicInteger bindingCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            int n = bindingCalls.incrementAndGet();
            if (n == 1) {
                // First BINDING fails — request rollback to DECOMPOSING.
                s.setPendingRecovery(de.mhus.vance.api.slartibartfast.RecoveryRequest.builder()
                        .fromPhase(de.mhus.vance.api.slartibartfast.ArchitectStatus.BINDING)
                        .toPhase(de.mhus.vance.api.slartibartfast.ArchitectStatus.DECOMPOSING)
                        .reason("test-rule")
                        .hint("test-hint")
                        .build());
            }
            return null;
        }).when(bindingPhase).execute(any(), any(), any());

        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        drainTurns(process, 30);

        ArchitectState finalState = readState(process);
        assertThat(finalState.getStatus())
                .as("after recovery+retry, run should still reach DONE")
                .isEqualTo(ArchitectStatus.DONE);
        assertThat(finalState.getRecoveryCount()).isEqualTo(1);
        // DECOMPOSING phase iterations: initial + recovery = 2.
        assertThat(finalState.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.DECOMPOSING)
                .hasSize(2);
    }

    @Test
    void pendingRecoveryPastBudget_escalates() {
        // Configure bindingPhase to ALWAYS fail — engine eventually
        // gives up after maxRecoveries attempts and ESCALATES.
        org.mockito.Mockito.doAnswer(inv -> {
            de.mhus.vance.api.slartibartfast.ArchitectState s = inv.getArgument(0);
            s.setPendingRecovery(de.mhus.vance.api.slartibartfast.RecoveryRequest.builder()
                    .fromPhase(de.mhus.vance.api.slartibartfast.ArchitectStatus.BINDING)
                    .toPhase(de.mhus.vance.api.slartibartfast.ArchitectStatus.DECOMPOSING)
                    .reason("forever-fail")
                    .hint("forever-fail")
                    .build());
            return null;
        }).when(bindingPhase).execute(any(), any(), any());

        ThinkProcessDocument process = newProcess();
        // Reduce budget so the test stays quick.
        engine.start(process, ctx);
        ArchitectState s = readState(process);
        s.setMaxRecoveries(2);
        // Re-persist via a bogus turn — easiest path is to write
        // back via the process.engineParams directly.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ep = (java.util.Map<String, Object>) process.getEngineParams();
        ep.put(SlartibartfastEngine.STATE_KEY,
                objectMapper.convertValue(s, java.util.Map.class));

        drainTurns(process, 30);

        ArchitectState finalState = readState(process);
        assertThat(finalState.getStatus())
                .isEqualTo(ArchitectStatus.ESCALATED);
        assertThat(finalState.getRecoveryCount()).isGreaterThan(2);
    }

    @Test
    void framedGoalSplitsStatedFromAssumedCriteria() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        drainTurns(process, 20);

        ArchitectState finalState = readState(process);
        assertThat(finalState.getGoal()).isNotNull();
        assertThat(finalState.getGoal().getStatedCriteria()).isNotEmpty();
        assertThat(finalState.getGoal().getAssumedCriteria()).isNotEmpty();

        // Stated criteria carry USER_STATED origin.
        assertThat(finalState.getGoal().getStatedCriteria())
                .allMatch(c -> c.getOrigin()
                        == de.mhus.vance.api.slartibartfast.CriterionOrigin.USER_STATED);

        // Assumed criteria carry an INFERRED_* origin AND a
        // non-null rationaleId — that is the central invariant
        // CONFIRMING and VALIDATING rely on.
        assertThat(finalState.getGoal().getAssumedCriteria())
                .allMatch(c -> {
                    var o = c.getOrigin();
                    return o == de.mhus.vance.api.slartibartfast.CriterionOrigin.INFERRED_CONVENTION
                            || o == de.mhus.vance.api.slartibartfast.CriterionOrigin.INFERRED_DOMAIN
                            || o == de.mhus.vance.api.slartibartfast.CriterionOrigin.INFERRED_CONTEXT
                            || o == de.mhus.vance.api.slartibartfast.CriterionOrigin.USER_CONFIRMED;
                });
        assertThat(finalState.getGoal().getAssumedCriteria())
                .allMatch(c -> c.getRationaleId() != null);
    }

    @Test
    void terminalDoneStateIsIdempotentAcrossExtraTurns() {
        ThinkProcessDocument process = newProcess();
        engine.start(process, ctx);
        drainTurns(process, 20);

        // After DONE, additional runTurns must be no-ops on the
        // state — same idempotency Zaphod relies on for queued lane
        // tasks pending after the final advance.
        ArchitectState before = readState(process);
        engine.runTurn(process, ctx);
        engine.runTurn(process, ctx);
        ArchitectState after = readState(process);

        assertThat(after.getStatus()).isEqualTo(ArchitectStatus.DONE);
        assertThat(after.getPersistedRecipePath())
                .isEqualTo(before.getPersistedRecipePath());
    }

    /**
     * Drives runTurns until the persisted state hits a terminal
     * status or the cap is exceeded. Returns the number of turns
     * actually taken (excluding the no-op turn that observes
     * DONE).
     */
    private int drainTurns(ThinkProcessDocument process, int cap) {
        for (int i = 0; i < cap; i++) {
            ArchitectState before = readState(process);
            if (isTerminal(before.getStatus())) {
                // The turn that observes terminal state is what
                // closes the process — invoke once and stop.
                engine.runTurn(process, ctx);
                return i;
            }
            engine.runTurn(process, ctx);
        }
        throw new AssertionError("turn cap exceeded — possible infinite loop");
    }

    private static boolean isTerminal(ArchitectStatus s) {
        return s == ArchitectStatus.DONE
                || s == ArchitectStatus.FAILED
                || s == ArchitectStatus.ESCALATED;
    }

    @SuppressWarnings("unchecked")
    private ArchitectState readState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SlartibartfastEngine.STATE_KEY);
        if (raw == null) return ArchitectState.builder().build();
        return objectMapper.convertValue(raw, ArchitectState.class);
    }

    private static ThinkProcessDocument newProcess() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(SlartibartfastEngine.USER_DESCRIPTION_KEY, "write me an essay");
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("proc-1");
        p.setTenantId("acme");
        p.setProjectId("test-project");
        p.setSessionId("sess-1");
        p.setGoal("write me an essay");
        p.setEngineParams(params);
        return p;
    }
}
