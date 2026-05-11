package de.mhus.vance.brain.thinkengine.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.history.BufferingHistoryTagSink;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.action.EngineAction;
import de.mhus.vance.brain.thinkengine.action.StructuredActionEngine.ActionTurnOutcome;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Engine-agnostic Plan-Mode dispatcher + handlers, extracted from
 * {@code ArthurEngine}. Behaviour assertions are 1:1 with the prior
 * inline implementation — what changed is the location of the code.
 *
 * <p>{@link PlanModeEventEmitter} and {@link ThinkProcessService} are
 * mocked. The history-tag sink is captured to verify {@code MODE:*}
 * / {@code PLAN_STEP_*} marker emission.
 */
class PlanModeServiceTest {

    private ThinkProcessService thinkProcessService;
    private PlanModeEventEmitter eventEmitter;
    private ChatMessageService chatMessageService;
    private InboxItemService inboxItemService;
    private PlanModeService service;
    private ThinkEngineContext ctx;
    /** Real buffer — we inspect via {@link BufferingHistoryTagSink#peek()}. */
    private BufferingHistoryTagSink sink;
    /** Per-emit record: each handler invocation is a separate snapshot, so we
     *  capture the state immediately after each emit() call. */
    private final List<Set<String>> emittedSnapshots = new ArrayList<>();

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        eventEmitter = mock(PlanModeEventEmitter.class);
        chatMessageService = mock(ChatMessageService.class);
        inboxItemService = mock(InboxItemService.class);
        MetricService metricService = new MetricService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new PlanModeService(
                thinkProcessService, eventEmitter,
                chatMessageService, inboxItemService, metricService);
        ctx = mock(ThinkEngineContext.class);
        emittedSnapshots.clear();
        // BufferingHistoryTagSink is final → use Mockito spy + doAnswer to
        // record per-emit snapshots without subclassing.
        sink = spy(new BufferingHistoryTagSink());
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Set<String> tags = (Set<String>) inv.getArgument(0);
            emittedSnapshots.add(tags == null ? Set.of() : Set.copyOf(tags));
            inv.callRealMethod();
            return null;
        }).when(sink).emit(any());
        when(ctx.historyTagSink()).thenReturn(sink);
    }

    // ─── dispatch routing ───────────────────────────────────────────

    @Test
    void dispatch_nonPlanModeAction_returnsNull() {
        EngineAction action = new EngineAction("ANSWER", "because", Map.of());
        ThinkProcessDocument p = process();

        assertThat(service.dispatch(action, p, ctx)).isNull();
    }

    @Test
    void dispatch_nullAction_returnsNull() {
        assertThat(service.dispatch(null, process(), ctx)).isNull();
    }

    @Test
    void dispatch_routesEachPlanModeType_toItsHandler() {
        ThinkProcessDocument p = process();
        when(thinkProcessService.updateMode(eq("p-1"), any())).thenReturn(true);

        // START_PLAN → no chat output, no awaiting input
        ActionTurnOutcome out = service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_START_PLAN, "ok", Map.of()),
                p, ctx);
        assertThat(out).isNotNull();
        assertThat(out.awaitingUserInput()).isFalse();
        assertThat(out.chatMessage()).isNull();
    }

    // ─── START_PLAN ─────────────────────────────────────────────────

    @Test
    void startPlan_planModeDisabled_returnsRejection_noModeChange() {
        ThinkProcessDocument p = process();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PlanModeActionSchema.ENGINE_PARAM_PLAN_MODE,
                PlanModeActionSchema.PLAN_MODE_DISABLED);
        p.setEngineParams(params);

        ActionTurnOutcome out = service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_START_PLAN, "ok", Map.of()),
                p, ctx);

        assertThat(out).isNotNull();
        assertThat(out.chatMessage()).contains("plan mode is disabled");
        verify(thinkProcessService, never()).updateMode(any(), any());
    }

    @Test
    void startPlan_default_flipsModeToExploring_emitsEvent() {
        ThinkProcessDocument p = process();
        when(thinkProcessService.updateMode("p-1", ProcessMode.EXPLORING)).thenReturn(true);

        service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_START_PLAN, "complex", Map.of()),
                p, ctx);

        verify(thinkProcessService).updateMode("p-1", ProcessMode.EXPLORING);
        verify(eventEmitter).emitModeChanged(any(), any(), eq(ProcessMode.EXPLORING));
        assertThat(p.getMode()).isEqualTo(ProcessMode.EXPLORING);
    }

    @Test
    void startPlan_processGone_returnsInternalError() {
        ThinkProcessDocument p = process();
        when(thinkProcessService.updateMode(any(), any())).thenReturn(false);

        ActionTurnOutcome out = service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_START_PLAN, "ok", Map.of()),
                p, ctx);

        assertThat(out).isNotNull();
        assertThat(out.chatMessage()).contains("failed to enter plan mode");
    }

    // ─── PROPOSE_PLAN ───────────────────────────────────────────────

    @Test
    void proposePlan_missingPlanText_isRejectedAsRetry() {
        ActionTurnOutcome out = service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_PROPOSE_PLAN, "...", Map.of()),
                process(), ctx);

        assertThat(out).isNotNull();
        assertThat(out.chatMessage()).contains("missing plan text");
        verify(thinkProcessService, never()).setTodos(any(), any());
    }

    @Test
    void proposePlan_missingTodos_isRejectedAsRetry() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PlanModeActionSchema.PARAM_PLAN, "# Plan body");
        params.put(PlanModeActionSchema.PARAM_SUMMARY, "summary");
        // todos absent

        ActionTurnOutcome out = service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_PROPOSE_PLAN, "...", params),
                process(), ctx);

        assertThat(out).isNotNull();
        assertThat(out.chatMessage()).contains("3–8 todos");
        verify(thinkProcessService, never()).setTodos(any(), any());
    }

    @Test
    void proposePlan_happyPath_setsTodos_flipsModeToPlanning_emitsTag() {
        ThinkProcessDocument p = process();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PlanModeActionSchema.PARAM_PLAN, "# Steps to refactor X");
        params.put(PlanModeActionSchema.PARAM_SUMMARY, "Refactor X");
        params.put(PlanModeActionSchema.PARAM_TODOS, List.of(
                Map.of("id", "1", "content", "Do A"),
                Map.of("id", "2", "content", "Do B"),
                Map.of("id", "3", "content", "Do C")));

        ActionTurnOutcome out = service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_PROPOSE_PLAN, "plan ready", params),
                p, ctx);

        assertThat(out).isNotNull();
        assertThat(out.chatMessage()).contains("Steps to refactor X");
        assertThat(out.awaitingUserInput()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TodoItem>> todosCap = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).setTodos(eq("p-1"), todosCap.capture());
        assertThat(todosCap.getValue()).hasSize(3);
        assertThat(p.getMode()).isEqualTo(ProcessMode.PLANNING);
        verify(eventEmitter).emitPlanProposed(any(), eq("Refactor X"), eq(1));
        assertThat(emittedSnapshots).anyMatch(set -> set.contains("MODE:plan"));
    }

    @Test
    void proposePlan_alreadyInPlanning_doesNotReEmitModeTag() {
        // Re-submitting an edited plan while already in PLANNING — the
        // ModeChange event must NOT fire (would spam the user-client),
        // and the history MODE:plan tag must NOT re-emit (Idempotenz).
        ThinkProcessDocument p = process();
        p.setMode(ProcessMode.PLANNING);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PlanModeActionSchema.PARAM_PLAN, "# Plan v2");
        params.put(PlanModeActionSchema.PARAM_SUMMARY, "v2");
        params.put(PlanModeActionSchema.PARAM_TODOS, List.of(
                Map.of("id", "1", "content", "Do A")));

        service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_PROPOSE_PLAN, "edit", params),
                p, ctx);

        verify(eventEmitter, never()).emitModeChanged(any(), any(), eq(ProcessMode.PLANNING));
        assertThat(emittedSnapshots).noneMatch(set -> set.contains("MODE:plan"));
    }

    // ─── START_EXECUTION ────────────────────────────────────────────

    @Test
    void startExecution_flipsModeAndEmitsExecuteTag() {
        ThinkProcessDocument p = process();
        p.setMode(ProcessMode.PLANNING);

        service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_START_EXECUTION, "go", Map.of()),
                p, ctx);

        verify(thinkProcessService).updateMode("p-1", ProcessMode.EXECUTING);
        verify(eventEmitter).emitModeChanged(any(), any(), eq(ProcessMode.EXECUTING));
        assertThat(p.getMode()).isEqualTo(ProcessMode.EXECUTING);
        assertThat(emittedSnapshots).anyMatch(set -> set.contains("MODE:execute"));
    }

    // ─── TODO_UPDATE ────────────────────────────────────────────────

    @Test
    void todoUpdate_emptyUpdates_isNoOp() {
        ActionTurnOutcome out = service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_TODO_UPDATE, "...", Map.of()),
                process(), ctx);

        assertThat(out).isNotNull();
        verify(thinkProcessService, never()).updateTodoStatuses(any(), anyMap());
        assertThat(emittedSnapshots).isEmpty();
    }

    @Test
    void todoUpdate_appliedStatuses_emitsCorrectTags() {
        ThinkProcessDocument p = process();
        ThinkProcessDocument refreshed = process();
        when(thinkProcessService.updateTodoStatuses(eq("p-1"), anyMap())).thenReturn(true);
        when(thinkProcessService.findById("p-1")).thenReturn(Optional.of(refreshed));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PlanModeActionSchema.PARAM_UPDATES, List.of(
                Map.of("id", "1", "status", "IN_PROGRESS"),
                Map.of("id", "2", "status", "COMPLETED"),
                Map.of("id", "3", "status", "PENDING"))); // PENDING → no tag

        service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_TODO_UPDATE, "...", params),
                p, ctx);

        assertThat(emittedSnapshots).hasSize(1);
        Set<String> tags = emittedSnapshots.get(0);
        assertThat(tags).contains("PLAN_STEP_STARTED:1", "PLAN_STEP_DONE:2");
        assertThat(tags).noneMatch(t -> t.contains(":3"));
    }

    @Test
    void todoUpdate_updateReturnsFalse_skipsTagEmission() {
        when(thinkProcessService.updateTodoStatuses(any(), any())).thenReturn(false);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PlanModeActionSchema.PARAM_UPDATES, List.of(
                Map.of("id", "1", "status", "COMPLETED")));

        service.dispatch(
                new EngineAction(PlanModeActionSchema.TYPE_TODO_UPDATE, "...", params),
                process(), ctx);

        assertThat(emittedSnapshots).isEmpty();
    }

    // ─── parseTodos / parseTodoUpdates ──────────────────────────────

    @Test
    void parseTodos_skipsMalformedEntries_keepsValidOnes() {
        Object raw = List.of(
                Map.of("id", "1", "content", "valid"),
                Map.of("id", "", "content", "blank-id"),
                Map.of("content", "no-id"),
                "not a map",
                Map.of("id", "2", "content", "valid 2", "activeForm", "Doing"));

        List<TodoItem> out = PlanModeService.parseTodos(raw);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getId()).isEqualTo("1");
        assertThat(out.get(1).getActiveForm()).isEqualTo("Doing");
    }

    @Test
    void parseTodoUpdates_skipsUnknownStatusValues() {
        Object raw = List.of(
                Map.of("id", "1", "status", "IN_PROGRESS"),
                Map.of("id", "2", "status", "BOGUS"),
                Map.of("id", "3", "status", "COMPLETED"));

        Map<String, TodoStatus> out = PlanModeService.parseTodoUpdates(raw);

        assertThat(out).hasSize(2)
                       .containsEntry("1", TodoStatus.IN_PROGRESS)
                       .containsEntry("3", TodoStatus.COMPLETED);
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static ThinkProcessDocument process() {
        return ThinkProcessDocument.builder()
                .id("p-1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("sess")
                .thinkEngine("arthur")
                .mode(ProcessMode.NORMAL)
                .engineParams(new LinkedHashMap<>())
                .todos(new ArrayList<>())
                .build();
    }

}
