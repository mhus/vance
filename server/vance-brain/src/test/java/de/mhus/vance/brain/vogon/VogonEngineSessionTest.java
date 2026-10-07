package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
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
 * The session-mode fork of {@link VogonEngine} (planning/vogon-agent-identity.md
 * §3.2): spawn forms (decision F4), the gate fast-path in front of the agent
 * (F1), and the chat form's re-arm at run terminals.
 */
class VogonEngineSessionTest {

    private static final String TENANT = "t";
    private static final String PROJECT = "p";

    private final MagratheaWorkflowService workflowService = mock(MagratheaWorkflowService.class);
    private final MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
    private final MagratheaGateChatAnswerService gateAnswers = mock(MagratheaGateChatAnswerService.class);
    private final VogonIntake intake = mock(VogonIntake.class);
    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final SessionService sessions = mock(SessionService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter> progress = mock(ObjectProvider.class);

    private final VogonSessionLoop sessionLoop = mock(VogonSessionLoop.class);
    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);

    private final VogonEngine engine = new VogonEngine(
            workflowService, projector, gateAnswers, intake, processes, sessions, progress, sessionLoop);

    @BeforeEach
    void setUp() {
        when(sessionLoop.turnFor(any(), any(), anyList()))
                .thenAnswer(inv -> new VogonSessionLoop.TurnOutcome("ok", false, false, false));
        when(intake.resolve(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> VogonIntake.Outcome.of(inv.getArgument(3), inv.getArgument(4)));
        when(workflowService.start(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("run-1");
    }

    // ── Spawn forms (F4) ────────────────────────────────────────────

    @Test
    void start_chatFormWaitsWithoutAGreetingTurn() {
        ThinkProcessDocument p = chatProcess(Map.of());

        engine.start(p, ctx);

        verify(processes).replaceEngineParams(eq("vogon-1"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry(VogonEngine.PARAM_CHAT_IDENTITY, true);
        verify(processes).updateStatus("vogon-1", ThinkProcessStatus.IDLE);
        verify(workflowService, never()).start(any(), any(), any(), any(), any(), any(), any(), any());
        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
    }

    @Test
    void start_workerFormWithAPlanAutoKicks() {
        givenPlan("release");

        engine.start(chatProcess(Map.of(VogonEngine.PARAM_WORKFLOW, "release")), ctx);

        verify(processes, org.mockito.Mockito.times(2)).replaceEngineParams(eq("vogon-1"), paramsCaptor.capture());
        // First write: the persisted spawn form; the second is rememberRunId.
        assertThat(paramsCaptor.getAllValues().get(0)).containsEntry(VogonEngine.PARAM_CHAT_IDENTITY, false);
        verify(workflowService).start(eq(TENANT), eq(PROJECT), eq("release"), any(), any(), any(), any(), any());
        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
    }

    // ── Gate fast-path in front of the agent (F1) ──────────────────

    @Test
    void steer_gateAnswerConsumed_neverWakesTheAgent() {
        when(gateAnswers.tryAnswer(TENANT, "run-7", "ok", "mara")).thenReturn(true);

        engine.steer(chatProcess(Map.of(VogonEngine.PARAM_RUN_ID, "run-7")), ctx, saidBy("mara", "ok"));

        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
        verify(processes).updateStatus("vogon-1", ThinkProcessStatus.IDLE);
    }

    @Test
    void steer_gateDoesNotConsume_theIdentityTurns() {
        when(gateAnswers.tryAnswer(eq(TENANT), eq("run-7"), anyString(), eq("mara")))
                .thenReturn(false);

        SteerMessage.UserChatInput said = saidBy("mara", "how is it going?");
        engine.steer(chatProcess(Map.of(VogonEngine.PARAM_RUN_ID, "run-7")), ctx, said);

        verify(sessionLoop).turnFor(any(), any(), eq(List.of(said)));
    }

    @Test
    void steer_nonHumanSender_skipsTheGateButReachesTheIdentity() {
        SteerMessage.UserChatInput steer = saidBy("process:arthur-1", "how far along?");

        engine.steer(chatProcess(Map.of(VogonEngine.PARAM_RUN_ID, "run-7")), ctx, steer);

        verify(gateAnswers, never()).tryAnswer(any(), any(), any(), any());
        verify(sessionLoop).turnFor(any(), any(), eq(List.of(steer)));
    }

    @Test
    void steer_workerFormWithoutARun_theMessageIsTheTask() {
        givenPlan("release", "version");
        ThinkProcessDocument p =
                chatProcess(Map.of(VogonEngine.PARAM_WORKFLOW, "release", VogonEngine.PARAM_CHAT_IDENTITY, false));

        engine.start(p, ctx); // defers: the plan wants a required parameter
        engine.steer(p, ctx, saidBy("mara", "release version 1.2.3"));

        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(workflowService)
                .start(eq(TENANT), eq(PROJECT), eq("release"), params.capture(), any(), any(), any(), any());
        assertThat(params.getValue()).containsEntry("task", "release version 1.2.3");
    }

    // ── Chat form terminals re-arm (F4), worker form closes ────────

    @Test
    void steer_chatFormTerminal_reArmsInsteadOfClosing() {
        engine.steer(
                chatProcess(Map.of(VogonEngine.PARAM_RUN_ID, "run-7")), ctx, runReports(ProcessEventType.DONE, "done"));

        verify(processes, never()).closeProcess(anyString(), any());
        verify(processes).updateStatus("vogon-1", ThinkProcessStatus.IDLE);
        verify(sessionLoop).turnFor(any(), any(), anyList());
    }

    @Test
    void steer_workerFormTerminal_closesLikeTheHeadlessPath() {
        ThinkProcessDocument p = chatProcess(Map.of(
                VogonEngine.PARAM_WORKFLOW, "release",
                VogonEngine.PARAM_CHAT_IDENTITY, false,
                VogonEngine.PARAM_RUN_ID, "run-7"));
        p.setParentProcessId("arthur-1");

        engine.steer(p, ctx, runReports(ProcessEventType.DONE, "done"));

        verify(processes).closeProcess("vogon-1", CloseReason.DONE);
        verify(processes).removeWorkerLink("arthur-1", "vogon-1");
        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
    }

    @Test
    void runTurn_chatFormBlocked_parksOnBlockedAfterTheTurn() {
        ThinkProcessDocument p = chatProcess(Map.of(VogonEngine.PARAM_RUN_ID, "run-7"));
        when(processes.isHaltRequested("vogon-1")).thenReturn(false);
        when(ctx.drainPending())
                .thenReturn(List.of(runReports(ProcessEventType.BLOCKED, "waiting at 'review'")))
                .thenReturn(List.of());

        engine.runTurn(p, ctx);

        verify(processes, never()).closeProcess(anyString(), any());
        verify(processes, org.mockito.Mockito.times(2)).updateStatus("vogon-1", ThinkProcessStatus.BLOCKED);
    }

    // ── suspend parks the lane, the run keeps working (§3.2) ───────

    @Test
    void suspend_chatFormParksTheLaneWithoutPausingTheRun() {
        engine.suspend(chatProcess(Map.of(VogonEngine.PARAM_RUN_ID, "run-7")), ctx);

        verify(workflowService, never()).pauseRun(any(), any(), any());
        verify(processes).updateStatus("vogon-1", ThinkProcessStatus.SUSPENDED);
    }

    @Test
    void suspend_workerFormStillPausesTheRun() {
        ThinkProcessDocument p = chatProcess(Map.of(
                VogonEngine.PARAM_WORKFLOW, "release",
                VogonEngine.PARAM_CHAT_IDENTITY, false,
                VogonEngine.PARAM_RUN_ID, "run-7"));

        engine.suspend(p, ctx);

        verify(workflowService).pauseRun(TENANT, PROJECT, "run-7");
        verify(processes).updateStatus("vogon-1", ThinkProcessStatus.SUSPENDED);
    }

    // ── helpers ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);

    /** A session-mode chat-form process (chatIdentity=true) unless overridden. */
    private static ThinkProcessDocument chatProcess(Map<String, Object> engineParams) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("vogon-1");
        p.setTenantId(TENANT);
        p.setProjectId(PROJECT);
        p.setSessionId("sess-1");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(VogonEngine.PARAM_SESSION_MODE, true);
        params.put(VogonEngine.PARAM_CHAT_IDENTITY, true);
        params.putAll(engineParams);
        p.setEngineParams(params);
        return p;
    }

    private static SteerMessage.UserChatInput saidBy(String fromUser, String text) {
        return new SteerMessage.UserChatInput(
                Instant.now(), null, fromUser, null, text, List.of(), false, null, null, null);
    }

    private static SteerMessage.ProcessEvent runReports(ProcessEventType type, String summary) {
        return new SteerMessage.ProcessEvent(Instant.now(), null, "", type, summary, null, null, null);
    }

    /** A plan that resolves, declaring the given parameters as required. */
    private void givenPlan(String name, String... requiredParams) {
        Map<String, MagratheaParameterSpec> parameters = new LinkedHashMap<>();
        for (String key : requiredParams) {
            parameters.put(key, new MagratheaParameterSpec("string", true, null));
        }
        ResolvedMagratheaWorkflow plan = new ResolvedMagratheaWorkflow(
                name,
                "",
                MagratheaWorkflowSource.PROJECT,
                null,
                null,
                null,
                null,
                "start",
                parameters,
                Map.of("start", terminalState()),
                MagratheaBoundsSpec.empty(),
                List.of(),
                List.of());
        when(intake.loadPlan(TENANT, PROJECT, name)).thenReturn(Optional.of(plan));
    }

    private static MagratheaStateSpec terminalState() {
        return new MagratheaStateSpec(
                "start",
                MagratheaTaskType.TERMINAL,
                null,
                null,
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                Map.of());
    }
}
