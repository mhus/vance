package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaParameterSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * When a Vogon spawn may wait for its task, when it has to fail instead,
 * and who is allowed to answer a gate from the chat.
 *
 * <p>The rule the spec states ({@code vogon-engine.md} §2) is that a spawn
 * which cannot work fails at once — "kein Prozess, der ewig idle steht".
 * Deferring is right only while a sentence could still supply the missing
 * piece.
 */
class VogonEngineStartTest {

    private static final String TENANT = "t";
    private static final String PROJECT = "p";

    private final MagratheaWorkflowService workflowService = mock(MagratheaWorkflowService.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final MagratheaGateChatAnswerService gateAnswers =
            mock(MagratheaGateChatAnswerService.class);
    private final VogonIntake intake = mock(VogonIntake.class);
    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final SessionService sessions = mock(SessionService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter> progress =
            mock(ObjectProvider.class);

    private final VogonEngine engine = new VogonEngine(
            workflowService, projector, gateAnswers, intake, processes, sessions, progress);

    @BeforeEach
    void setUp() {
        // The real intake hands the caller params straight through when it
        // has nothing to read; the interesting assertions here are about
        // what reaches it, not what it does.
        when(intake.resolve(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> VogonIntake.Outcome.of(
                        inv.getArgument(3), inv.getArgument(4)));
        when(workflowService.start(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("run-1");
    }

    // ── Deferring ──────────────────────────────────────────────────

    @Test
    void start_nothingDeclared_waitsForTheTaskMessage() {
        engine.start(process(Map.of()), null);

        verify(processes).updateStatus("vogon-1", ThinkProcessStatus.IDLE);
        verify(processes, never()).closeProcess(anyString(), any());
    }

    @Test
    void start_declaredPlanMissingAParameter_waitsForTheTaskMessage() {
        givenPlan("release", "version");

        engine.start(process(Map.of(VogonEngine.PARAM_WORKFLOW, "release")), null);

        verify(processes).updateStatus("vogon-1", ThinkProcessStatus.IDLE);
        verify(workflowService, never()).start(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void start_declaredPlanFullyParameterised_startsImmediately() {
        givenPlan("release");

        engine.start(process(Map.of(VogonEngine.PARAM_WORKFLOW, "release")), null);

        verify(workflowService).start(
                eq(TENANT), eq(PROJECT), eq("release"), any(), any(), any(), any(), any());
    }

    // ── Failing at once ────────────────────────────────────────────

    @Test
    void start_declaredPlanDoesNotResolve_failsAtOnceAndClosesTheProcess() {
        // A typo in `workflow:`, or a plan document that does not parse. No
        // amount of waiting makes that name resolve, and an IDLE process
        // with no run behind it is invisible to every watchdog there is:
        // there is no task, so nothing ever times it out.
        when(intake.loadPlan(TENANT, PROJECT, "relase")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                engine.start(process(Map.of(VogonEngine.PARAM_WORKFLOW, "relase")), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relase");

        verify(processes).closeProcess("vogon-1", CloseReason.STALE);
        verify(processes, never()).updateStatus("vogon-1", ThinkProcessStatus.IDLE);
    }

    @Test
    void start_declaredPlanDoesNotResolve_neverAsksAModelToPickOne() {
        when(intake.loadPlan(TENANT, PROJECT, "relase")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                engine.start(process(Map.of(VogonEngine.PARAM_WORKFLOW, "relase")), null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(intake, never()).resolve(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void start_intakeNoneWithoutAPlan_failsAtOnceInsteadOfIdling() {
        // `intake: none` says this plan is never fed from prose, so nothing
        // a later message could carry would change the outcome.
        assertThatThrownBy(() -> engine.start(
                process(Map.of(VogonEngine.PARAM_INTAKE, VogonIntake.INTAKE_NONE)), null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(processes).closeProcess("vogon-1", CloseReason.STALE);
        verify(processes, never()).updateStatus("vogon-1", ThinkProcessStatus.IDLE);
    }

    // ── Control params ─────────────────────────────────────────────

    @Test
    void start_intakeIsNotHandedToThePlanAsAParameter() {
        // `intake` says how the plan is found, not what it runs with. Left
        // in, it lands in StartRecord.params and overwrites the default of a
        // plan parameter that happens to share the name.
        givenPlan("release");

        engine.start(process(new LinkedHashMap<>(Map.of(
                VogonEngine.PARAM_WORKFLOW, "release",
                VogonEngine.PARAM_INTAKE, VogonIntake.INTAKE_NONE,
                "channel", "beta"))), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).start(
                eq(TENANT), eq(PROJECT), eq("release"), params.capture(),
                any(), any(), any(), any());
        assertThat(params.getValue()).doesNotContainKey(VogonEngine.PARAM_INTAKE);
        assertThat(params.getValue()).containsEntry("channel", "beta");
    }

    // ── Who may answer a gate ──────────────────────────────────────

    @Test
    void chatAnswer_fromAnotherProcess_isNotTreatedAsAGateAnswer() {
        // An orchestrator steering this child through process_message sends
        // the very same message type. Answering the gate on its behalf —
        // stamped as the session owner, because that was the fallback — was
        // an approval nobody gave.
        engine.steer(withRun("run-7"), null, saidBy("process:arthur-1", "ok"));

        verify(gateAnswers, never()).tryAnswer(any(), any(), any(), any());
    }

    @Test
    void chatAnswer_fromAServiceAccount_isNotTreatedAsAGateAnswer() {
        engine.steer(withRun("run-7"), null, saidBy("_magrathea", "ok"));

        verify(gateAnswers, never()).tryAnswer(any(), any(), any(), any());
    }

    @Test
    void chatAnswer_fromAPerson_reachesTheGateUnderTheirOwnName() {
        when(gateAnswers.tryAnswer(TENANT, "run-7", "ok", "mara")).thenReturn(true);

        engine.steer(withRun("run-7"), null, saidBy("mara", "ok"));

        verify(gateAnswers).tryAnswer(TENANT, "run-7", "ok", "mara");
    }

    // ── helpers ────────────────────────────────────────────────────

    private static ThinkProcessDocument process(Map<String, Object> engineParams) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("vogon-1");
        p.setTenantId(TENANT);
        p.setProjectId(PROJECT);
        p.setSessionId("sess-1");
        p.setEngineParams(new LinkedHashMap<>(engineParams));
        return p;
    }

    private ThinkProcessDocument withRun(String runId) {
        ThinkProcessDocument p = process(Map.of(VogonEngine.PARAM_RUN_ID, runId));
        when(processes.findById("vogon-1")).thenReturn(Optional.of(p));
        return p;
    }

    private static SteerMessage.UserChatInput saidBy(String fromUser, String text) {
        return new SteerMessage.UserChatInput(
                Instant.now(), null, fromUser, null, text,
                List.of(), false, null, null, null);
    }

    /** A plan that resolves, declaring the given parameters as required. */
    private void givenPlan(String name, String... requiredParams) {
        Map<String, MagratheaParameterSpec> parameters = new LinkedHashMap<>();
        for (String key : requiredParams) {
            parameters.put(key, new MagratheaParameterSpec("string", true, null));
        }
        ResolvedMagratheaWorkflow plan = new ResolvedMagratheaWorkflow(
                name, "", MagratheaWorkflowSource.PROJECT,
                null, null, null, null, "start",
                parameters, Map.of("start", terminalState()),
                MagratheaBoundsSpec.empty(), List.of(), List.of());
        when(intake.loadPlan(TENANT, PROJECT, name)).thenReturn(Optional.of(plan));
    }

    private static MagratheaStateSpec terminalState() {
        return new MagratheaStateSpec(
                "start", MagratheaTaskType.TERMINAL, null, null, null, null,
                List.of(), Map.of(), Map.of(), List.of(), MagratheaRetrySpec.none(), Map.of());
    }
}
