package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.ResolvedBy;
import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
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
 * The session-mode fork of {@link MarvinEngine}
 * (planning/marvin-agent-identity.md): spawn forms (decision F1), the
 * identity turn in front of the tree step (F3), the chat form's terminal
 * narration — the live fix: a parentless Marvin's result never reached the
 * chat — and the marvin_start/marvin_stop backends.
 */
class MarvinEngineSessionTest {

    private static final String TENANT = "t";
    private static final String PROJECT = "p";

    private final MarvinNodeService nodeService = mock(MarvinNodeService.class);
    private final MarvinProperties properties = new MarvinProperties();
    private final MaximegalonService inboxItems = mock(MaximegalonService.class);
    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final de.mhus.vance.shared.chat.ChatMessageService chatLog =
            mock(de.mhus.vance.shared.chat.ChatMessageService.class);
    private final de.mhus.vance.brain.thinkengine.ProcessEventEmitter events =
            mock(de.mhus.vance.brain.thinkengine.ProcessEventEmitter.class);
    private final MarvinSessionLoop sessionLoop = mock(MarvinSessionLoop.class);
    private final de.mhus.vance.brain.arthur.PlanModeEventEmitter planEvents =
            mock(de.mhus.vance.brain.arthur.PlanModeEventEmitter.class);
    private final ThinkEngineService engineService = mock(ThinkEngineService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ThinkEngineService> engineProvider = mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final MarvinEngine engine = new MarvinEngine(
            nodeService,
            properties,
            inboxItems,
            processes,
            chatLog,
            mock(de.mhus.vance.brain.recipe.RecipeResolver.class),
            mock(de.mhus.vance.brain.recipe.RecipeLoader.class),
            mock(PhaseOutputParser.class),
            mock(PlanSnapshotRenderer.class),
            mock(de.mhus.vance.brain.progress.LlmCallTracker.class),
            mock(de.mhus.vance.brain.progress.ProgressEmitter.class),
            mock(de.mhus.vance.brain.thinkengine.EnginePromptResolver.class),
            mock(de.mhus.vance.brain.thinkengine.SystemPromptComposer.class),
            mock(de.mhus.vance.brain.ai.EngineChatFactory.class),
            mock(tools.jackson.databind.ObjectMapper.class),
            events,
            mock(de.mhus.vance.brain.scheduling.LaneScheduler.class),
            mock(DocumentExpander.class),
            mock(de.mhus.vance.shared.workspace.WorkspaceService.class),
            mock(de.mhus.vance.shared.document.DocumentService.class),
            engineProvider,
            mock(de.mhus.vance.brain.inherit.ParentContextSpawnHelper.class),
            mock(de.mhus.vance.brain.inherit.ParentContextRenderer.class),
            sessionLoop,
            planEvents);

    private final ThinkEngineContext ctx = mock(ThinkEngineContext.class);
    private final ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);

    @BeforeEach
    void setUp() {
        when(sessionLoop.turnFor(any(), any(), anyList()))
                .thenAnswer(inv -> new MarvinSessionLoop.TurnOutcome("ok", false, false, false));
        when(engineProvider.getObject()).thenReturn(engineService);
        when(processes.claimFinalReplyEmission(anyString())).thenReturn(true);
    }

    // ── helpers ─────────────────────────────────────────────────────

    // ── Tool surface (F5) ────────────────────────────────────────────

    /**
     * The live bug (vance-brain1.log): the identity's prompt announced
     * marvin_start, but allowedTools() — the surface filter for the whole
     * process — didn't carry it, so the call answered "not available to
     * this engine". The marvin_* tools must be in the allowed set.
     */
    @Test
    void allowedToolsCarryTheIdentityTools() {
        assertThat(engine.allowedTools()).contains("marvin_start", "marvin_stop", "marvin_status");
    }

    private static ThinkProcessDocument sessionProcess(String parentProcessId, String goal) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("marvin-1");
        p.setTenantId(TENANT);
        p.setProjectId(PROJECT);
        p.setSessionId("s1");
        p.setThinkEngine(MarvinEngine.NAME);
        p.setParentProcessId(parentProcessId);
        p.setGoal(goal);
        p.setEngineParams(new LinkedHashMap<>(Map.of(MarvinEngine.PARAM_SESSION_MODE, true)));
        return p;
    }

    private static SteerMessage.UserChatInput saidBy(String fromUser, String content) {
        return new SteerMessage.UserChatInput(Instant.now(), null, fromUser, content);
    }

    private static MarvinNodeDocument node(NodeStatus status) {
        MarvinNodeDocument n = new MarvinNodeDocument();
        n.setId("node-" + status.name().toLowerCase());
        n.setTenantId(TENANT);
        n.setProcessId("marvin-1");
        n.setGoal("Investigate the cache bug");
        n.setTaskKind(TaskKind.WORKER);
        n.setStatus(status);
        return n;
    }

    // ── Spawn forms (F1) ────────────────────────────────────────────

    @Test
    void start_chatFormWithoutGoalParksIdle_NoGreetingTurn() {
        ThinkProcessDocument p = sessionProcess(null, null);

        engine.start(p, ctx);

        verify(processes).replaceEngineParams(eq("marvin-1"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry(MarvinEngine.PARAM_CHAT_IDENTITY, true);
        verify(processes).updateStatus("marvin-1", ThinkProcessStatus.IDLE);
        verify(nodeService, never()).createRoot(any(), any(), any(), any(), any());
        verify(events, never()).scheduleTurn(any());
        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
    }

    @Test
    void start_chatFormWithGoalAutoKicks() {
        ThinkProcessDocument p = sessionProcess(null, "Why does the cache thrash?");

        engine.start(p, ctx);

        verify(processes).replaceEngineParams(eq("marvin-1"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry(MarvinEngine.PARAM_CHAT_IDENTITY, true);
        verify(nodeService).createRoot(eq(TENANT), eq("marvin-1"), eq("Why does the cache thrash?"), any(), any());
        verify(processes).resetFinalReplyEmission("marvin-1");
        verify(events).scheduleTurn("marvin-1");
        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
    }

    @Test
    void start_workerFormPersistsChatIdentityFalse() {
        ThinkProcessDocument p = sessionProcess("parent-1", "Summarize the repo layout");

        engine.start(p, ctx);

        verify(processes).replaceEngineParams(eq("marvin-1"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry(MarvinEngine.PARAM_CHAT_IDENTITY, false);
        verify(nodeService).createRoot(any(), any(), any(), any(), any());
    }

    @Test
    void start_workerFormWithoutGoalFailsFast() {
        ThinkProcessDocument p = sessionProcess("parent-1", null);

        assertThatThrownBy(() -> engine.start(p, ctx)).isInstanceOf(IllegalStateException.class);
        verify(nodeService, never()).createRoot(any(), any(), any(), any(), any());
    }

    @Test
    void start_headlessIgnoresTheSessionFork() {
        ThinkProcessDocument p = sessionProcess("parent-1", "the goal");
        p.setEngineParams(new LinkedHashMap<>());

        engine.start(p, ctx);

        verify(processes, never()).replaceEngineParams(any(), any());
        verify(nodeService).createRoot(any(), any(), any(), any(), any());
    }

    // ── steer: chat input reaches the identity directly (F3) ───────

    @Test
    void steer_chatInputWakesTheIdentity_ScheduleTurnStaysForTheTree() {
        engine.steer(sessionProcess(null, null), ctx, saidBy("mara", "how is it going?"));

        verify(sessionLoop).turnFor(any(), any(), anyList());
        verify(events, never()).scheduleTurn(any());
    }

    @Test
    void steer_blankChatInputIsDropped() {
        engine.steer(sessionProcess(null, null), ctx, saidBy("mara", "   "));

        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
        verify(events, never()).scheduleTurn(any());
    }

    @Test
    void steer_headlessChatInputStillJustSchedules() {
        ThinkProcessDocument p = sessionProcess(null, null);
        p.setEngineParams(new LinkedHashMap<>());

        engine.steer(p, ctx, saidBy("mara", "hello?"));

        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
        verify(events).scheduleTurn("marvin-1");
    }

    // ── runTurn fork (F3): UCI batch = identity turn, tree skips ────

    @Test
    void runTurn_chatBatchGoesToTheIdentity_TreeStepSkips() {
        when(ctx.drainPending()).thenReturn(List.of(saidBy("mara", "start thinking about X")));

        engine.runTurn(sessionProcess(null, null), ctx);

        verify(sessionLoop).turnFor(any(), eq(ctx), anyList());
        verify(nodeService, never()).findNextActionableNode(any(), any());
    }

    @Test
    void runTurn_chatBatchReschedulesALiveTree() {
        when(ctx.drainPending()).thenReturn(List.of(saidBy("mara", "status?")));
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.of(node(NodeStatus.RUNNING)));
        when(nodeService.isTreeTerminal("marvin-1")).thenReturn(false);

        engine.runTurn(sessionProcess(null, null), ctx);

        verify(events).scheduleTurn("marvin-1");
    }

    @Test
    void runTurn_treeOnlyBatchWalksAndNeverWakesTheIdentity() {
        when(ctx.drainPending()).thenReturn(List.of());
        when(nodeService.findNextActionableNode(any(), any())).thenReturn(Optional.empty());
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.empty());

        engine.runTurn(sessionProcess(null, null), ctx);

        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
        verify(processes).updateStatus("marvin-1", ThinkProcessStatus.IDLE);
    }

    // ── Terminal narration (F4) — the live fix ──────────────────────

    @Test
    void terminal_chatFormNarratesTheResultAndSurvivesTheRun() {
        when(ctx.drainPending()).thenReturn(List.of());
        when(nodeService.findNextActionableNode(any(), any())).thenReturn(Optional.empty());
        MarvinNodeDocument root = node(NodeStatus.DONE);
        root.setId("root-1");
        root.setArtifacts(new LinkedHashMap<>(Map.of("result", "The cache thrashes because of X.")));
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.of(root));
        when(nodeService.isTreeTerminal("marvin-1")).thenReturn(true);
        when(nodeService.findChildren("marvin-1", "root-1")).thenReturn(List.of());
        when(nodeService.listAll("marvin-1")).thenReturn(List.of(root));
        when(nodeService.hasRunningNodes("marvin-1")).thenReturn(false);
        when(nodeService.hasWaitingNodes("marvin-1")).thenReturn(false);

        engine.runTurn(sessionProcess(null, null), ctx);

        // The identity is woken exactly once with the full result.
        ArgumentCaptor<List<SteerMessage>> wakeCaptor = ArgumentCaptor.forClass(List.class);
        verify(sessionLoop, times(1)).turnFor(any(), eq(ctx), wakeCaptor.capture());
        SteerMessage wake = wakeCaptor.getValue().get(0);
        assertThat(wake).isInstanceOf(SteerMessage.ProcessEvent.class);
        SteerMessage.ProcessEvent event = (SteerMessage.ProcessEvent) wake;
        assertThat(event.type()).isEqualTo(ProcessEventType.DONE);
        assertThat(event.payload()).containsEntry("result", "The cache thrashes because of X.");
        // The chat form survives: no closeProcess, parked IDLE.
        verify(processes, never()).closeProcess(any(), any());
        verify(processes).updateStatus("marvin-1", ThinkProcessStatus.IDLE);
    }

    @Test
    void terminal_narratesOnlyOncePerRun() {
        when(ctx.drainPending()).thenReturn(List.of());
        when(nodeService.findNextActionableNode(any(), any())).thenReturn(Optional.empty());
        MarvinNodeDocument root = node(NodeStatus.DONE);
        root.setId("root-1");
        root.setArtifacts(new LinkedHashMap<>(Map.of("result", "the result")));
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.of(root));
        when(nodeService.isTreeTerminal("marvin-1")).thenReturn(true);
        when(nodeService.findChildren("marvin-1", "root-1")).thenReturn(List.of());
        when(nodeService.listAll("marvin-1")).thenReturn(List.of(root));
        when(nodeService.hasRunningNodes("marvin-1")).thenReturn(false);
        when(nodeService.hasWaitingNodes("marvin-1")).thenReturn(false);
        ThinkProcessDocument p = sessionProcess(null, null);

        engine.runTurn(p, ctx);
        // The latch is claimed — a second walk (late duplicate child event)
        // must not narrate again.
        when(processes.claimFinalReplyEmission(anyString())).thenReturn(false);
        engine.runTurn(p, ctx);

        verify(sessionLoop, times(1)).turnFor(any(), eq(ctx), anyList());
    }

    @Test
    void terminal_workerFormKeepsTheHeadlessContract() {
        when(ctx.drainPending()).thenReturn(List.of());
        when(nodeService.findNextActionableNode(any(), any())).thenReturn(Optional.empty());
        when(nodeService.isTreeTerminal("marvin-1")).thenReturn(true);
        when(nodeService.hasRunningNodes("marvin-1")).thenReturn(false);
        when(nodeService.hasWaitingNodes("marvin-1")).thenReturn(false);

        engine.runTurn(sessionProcess("parent-1", "the goal"), ctx);

        // Worker form: no chat narration — the headless close DONE path.
        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
        verify(processes).closeProcess("marvin-1", de.mhus.vance.api.thinkprocess.CloseReason.DONE);
    }

    @Test
    void noTreeYet_NoNarration() {
        when(ctx.drainPending()).thenReturn(List.of());
        when(nodeService.findNextActionableNode(any(), any())).thenReturn(Optional.empty());
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.empty());

        engine.runTurn(sessionProcess(null, null), ctx);

        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
    }

    // ── Open-question relay (F6) ────────────────────────────────────

    @Test
    void relay_wakesTheIdentityOncePerInboxQuestion() {
        MarvinNodeDocument waiting = node(NodeStatus.WAITING);
        waiting.setInboxItemId("item-1");
        waiting.setTaskSpec(new LinkedHashMap<>(Map.of("title", "Which dataset?")));
        ThinkProcessDocument p = sessionProcess(null, null);

        engine.relayOpenQuestion(p, ctx, waiting);

        ArgumentCaptor<List<SteerMessage>> wakeCaptor = ArgumentCaptor.forClass(List.class);
        verify(sessionLoop, times(1)).turnFor(any(), eq(ctx), wakeCaptor.capture());
        SteerMessage.ProcessEvent event =
                (SteerMessage.ProcessEvent) wakeCaptor.getValue().get(0);
        assertThat(event.type()).isEqualTo(ProcessEventType.BLOCKED);
        assertThat(event.payload()).containsEntry("question", "Which dataset?").containsEntry("inboxItemId", "item-1");
        // The relay flag is persisted — the second call stays silent.
        assertThat(waiting.isChatRelayEmitted()).isTrue();
        engine.relayOpenQuestion(p, ctx, waiting);
        verify(sessionLoop, times(1)).turnFor(any(), any(), anyList());
    }

    @Test
    void relay_onlyForWaitingNodesWithAnInboxItem() {
        ThinkProcessDocument p = sessionProcess(null, null);

        engine.relayOpenQuestion(p, ctx, node(NodeStatus.RUNNING));
        engine.relayOpenQuestion(p, ctx, node(NodeStatus.WAITING)); // no inbox item

        verify(sessionLoop, never()).turnFor(any(), any(), anyList());
    }

    // ── marvin_start / marvin_stop backends (F2) ─────────────────────

    @Test
    void startTree_deletesTheOldTreeAndReArmsTheLatch() {
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.of(node(NodeStatus.DONE)));
        when(nodeService.isTreeTerminal("marvin-1")).thenReturn(true);

        engine.startTree(sessionProcess(null, null), "the refined goal");

        verify(nodeService).deleteTree("marvin-1");
        verify(nodeService).createRoot(eq(TENANT), eq("marvin-1"), eq("the refined goal"), any(), any());
        verify(processes).resetFinalReplyEmission("marvin-1");
        verify(events).scheduleTurn("marvin-1");
    }

    @Test
    void stopTree_marksNodesTerminalAndResolvesInboxItems() {
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.of(node(NodeStatus.RUNNING)));
        when(nodeService.isTreeTerminal("marvin-1")).thenReturn(false);
        MarvinNodeDocument running = node(NodeStatus.RUNNING);
        running.setSpawnedProcessId("child-1");
        MarvinNodeDocument pending = node(NodeStatus.PENDING);
        MarvinNodeDocument waiting = node(NodeStatus.WAITING);
        waiting.setInboxItemId("item-9");
        when(nodeService.listAll("marvin-1")).thenReturn(List.of(running, pending, waiting));
        when(processes.findById("child-1")).thenReturn(Optional.of(sessionProcess(null, "child goal")));

        boolean stopped = engine.stopTree(sessionProcess(null, null));

        assertThat(stopped).isTrue();
        verify(nodeService).markFailed(running, "run stopped by request");
        verify(nodeService).markFailed(waiting, "run stopped by request");
        verify(nodeService).markSkipped(pending, "run stopped by request");
        verify(engineService).stop(any());
        ArgumentCaptor<de.mhus.vance.api.inbox.AnswerPayload> answerCaptor =
                ArgumentCaptor.forClass(de.mhus.vance.api.inbox.AnswerPayload.class);
        verify(inboxItems).answer(eq(TENANT), eq("item-9"), answerCaptor.capture(), eq(ResolvedBy.AUTO_RESOLVER));
        assertThat(answerCaptor.getValue().getOutcome()).isEqualTo(AnswerOutcome.UNDECIDABLE);
        // A [tree] note marks the stop in the history.
        ArgumentCaptor<de.mhus.vance.shared.chat.ChatMessageDocument> noteCaptor =
                ArgumentCaptor.forClass(de.mhus.vance.shared.chat.ChatMessageDocument.class);
        verify(chatLog).append(noteCaptor.capture());
        assertThat(noteCaptor.getValue().getContent()).startsWith("[tree] run stopped by request");
        verify(events).scheduleTurn("marvin-1");
    }

    @Test
    void stopTree_withoutALiveTreeIsANoOp() {
        when(nodeService.findRoot("marvin-1")).thenReturn(Optional.empty());

        assertThat(engine.stopTree(sessionProcess(null, null))).isFalse();

        verify(nodeService, never()).markFailed(any(), any());
        verify(nodeService, never()).markSkipped(any(), any());
    }

    // ── readTreeStatus (//marvin, marvin_status) ────────────────────

    @Test
    void readTreeStatus_reportsLiveCountsOpenQuestionsAndResult() {
        MarvinNodeDocument root = node(NodeStatus.DONE);
        root.setId("root-1");
        root.setArtifacts(new LinkedHashMap<>(Map.of("result", "final answer")));
        MarvinNodeDocument running = node(NodeStatus.RUNNING);
        MarvinNodeDocument waiting = node(NodeStatus.WAITING);
        waiting.setInboxItemId("item-2");
        when(nodeService.listAll("marvin-1")).thenReturn(List.of(root, running, waiting));
        MaximegalonDocument item = new MaximegalonDocument();
        item.setId("item-2");
        item.setStatus(de.mhus.vance.api.inbox.MaximegalonStatus.PENDING);
        item.setTitle("Which dataset?");
        when(inboxItems.findById(TENANT, "item-2")).thenReturn(Optional.of(item));

        Map<String, Object> status = engine.readTreeStatus(sessionProcess(null, null));

        assertThat(status)
                .containsEntry("run", true)
                .containsEntry("lifecycle", "LIVE")
                .containsEntry("done", 1)
                .containsEntry("running", 1)
                .containsEntry("waiting", 1)
                .containsEntry("nodeCount", 3)
                .containsEntry("currentNode", "Investigate the cache bug")
                .containsEntry("openQuestions", List.of("Which dataset?"));
        assertThat(status).doesNotContainKey("result"); // still live — no result yet
    }

    @Test
    void readTreeStatus_emptyTreeReportsNoRun() {
        when(nodeService.listAll("marvin-1")).thenReturn(List.of());

        Map<String, Object> status = engine.readTreeStatus(sessionProcess(null, null));

        assertThat(status).containsEntry("run", false);
    }
}
