package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * For-each over the items of a workflow task: one sub-run per element, one after the other.
 * The task row is a small fake so the state the loop leaves there is read back like in
 * production.
 */
class MagratheaForeachTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final MagratheaWorkflowService workflows = mock(MagratheaWorkflowService.class);
    private final MagratheaTaskService tasks = mock(MagratheaTaskService.class);
    private final MagratheaCompletionEventBus bus = mock(MagratheaCompletionEventBus.class);
    private final MagratheaForeach foreach = new MagratheaForeach(workflows, tasks, bus, mapper);

    /** The foreach state as the loop last wrote it, and the sub-run linked to the task. */
    private String savedState;

    private final List<String> startedRuns = new ArrayList<>();

    @BeforeEach
    void wire() {
        org.mockito.Mockito.doAnswer(inv -> {
                    savedState = inv.getArgument(1);
                    return null;
                })
                .when(tasks)
                .setForeachState(any(), any());
        when(workflows.start(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            String id = "sub-" + (startedRuns.size() + 1);
            startedRuns.add(id);
            return id;
        });
        when(workflows.currentStatus(any(), any(), any())).thenReturn(MagratheaRunStatus.RUNNING);
    }

    // ──────────────────── begin ────────────────────

    @Test
    void itemsThatAreNotAListEndTheTaskWithoutStartingAnything() {
        Optional<TaskOutcome> ended = foreach.begin(ctx(), "handle-one", Map.of(), "just text", "item", false, 100);

        assertThat(ended.orElseThrow().outcome()).isEqualTo("failure");
        assertThat(ended.get().errorMessage()).contains("items must be a list").contains("text");
        verify(workflows, never()).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEmptyStringFromAMissingReferenceIsSaidToBeNothing() {
        Optional<TaskOutcome> ended = foreach.begin(ctx(), "handle-one", Map.of(), "", "item", false, 100);

        assertThat(ended.orElseThrow().errorMessage()).contains("resolved to nothing");
    }

    @Test
    void moreItemsThanMaxItemsAreRefusedBeforeTheFirstRuns() {
        Optional<TaskOutcome> ended = foreach.begin(ctx(), "handle-one", Map.of(), List.of(1, 2, 3), "item", false, 2);

        assertThat(ended.orElseThrow().outcome()).isEqualTo("failure");
        assertThat(ended.get().errorMessage()).contains("3 items").contains("maxItems 2");
        verify(workflows, never()).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEmptyListEndsAtOnceWithAnEmptyResultList() {
        Optional<TaskOutcome> ended = foreach.begin(ctx(), "handle-one", Map.of(), List.of(), "item", false, 100);

        assertThat(ended.orElseThrow().outcome()).isEqualTo("success");
        assertThat(ended.get().output().isArray()).isTrue();
        assertThat(ended.get().output()).isEmpty();
        verify(workflows, never()).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theFirstElementStartsWithTheSharedParamsTheElementTheIndexAndTheCount() {
        Optional<TaskOutcome> waiting = foreach.begin(
                ctx(),
                "handle-one",
                Map.of("channel", "#ops"),
                List.of(Map.of("id", "A-1"), Map.of("id", "B-2")),
                "ticket",
                false,
                100);

        assertThat(waiting).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(workflows)
                .start(
                        eq("acme"),
                        eq("proj"),
                        eq("handle-one"),
                        params.capture(),
                        eq("alice"),
                        eq("r1"),
                        eq("handle_each"));
        assertThat(params.getValue())
                .containsEntry("channel", "#ops")
                .containsEntry("ticket", Map.of("id", "A-1"))
                .containsEntry("index", 0)
                .containsEntry("count", 2);
        verify(tasks).linkSubWorkflow("task-1", "sub-1");
    }

    @Test
    void theProgressIsWrittenBeforeTheSubRunExists() {
        // The end of the sub-run must find the loop's state, however quickly it comes.
        foreach.begin(ctx(), "handle-one", Map.of(), List.of("a", "b"), "item", false, 100);

        var order = inOrder(tasks, workflows);
        order.verify(tasks).setForeachState(eq("task-1"), any());
        order.verify(workflows).start(any(), any(), any(), any(), any(), any(), any());
        order.verify(tasks).linkSubWorkflow("task-1", "sub-1");
    }

    @Test
    void ifTheFirstElementWillNotStartTheTaskFails() {
        when(workflows.start(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new MagratheaWorkflowService.MagratheaWorkflowException("Workflow 'nope' not found"));

        Optional<TaskOutcome> ended = foreach.begin(ctx(), "nope", Map.of(), List.of("a"), "item", false, 100);

        assertThat(ended.orElseThrow().outcome()).isEqualTo("failure");
        assertThat(ended.get().errorMessage()).contains("not found");
        verify(tasks, never()).linkSubWorkflow(any(), any());
    }

    // ──────────────────── onElementDone ────────────────────

    @Test
    void anOrdinarySubWorkflowTaskIsNotTheLoopsBusiness() {
        MagratheaTaskDocument plain = task(null);

        assertThat(foreach.onElementDone(plain, "success", null, null)).isFalse();
        verify(bus, never()).publish(any());
    }

    @Test
    void whenAnElementEndsTheNextOneStartsWithItsOwnItemAndIndex() {
        begin(List.of("a", "b", "c"), false);

        assertThat(foreach.onElementDone(task(savedState), "success", result("{\"n\":1}"), null))
                .isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(workflows, org.mockito.Mockito.times(2))
                .start(any(), any(), any(), params.capture(), any(), any(), any());
        assertThat(params.getAllValues().get(1)).containsEntry("item", "b").containsEntry("index", 1);
        verify(tasks).linkSubWorkflow("task-1", "sub-2");
        verify(bus, never()).publish(any());
    }

    @Test
    void afterTheLastElementTheTaskEndsWithTheResultsInOrder() {
        begin(List.of("a", "b"), false);
        foreach.onElementDone(task(savedState), "success", result("{\"v\":\"first\"}"), null);

        foreach.onElementDone(task(savedState), "success", result("{\"v\":\"second\"}"), null);

        TaskCompletedEvent done = published();
        assertThat(done.outcome()).isEqualTo("success");
        assertThat(done.taskId()).isEqualTo("task-1");
        assertThat(done.output().get(0).get("v").asString()).isEqualTo("first");
        assertThat(done.output().get(1).get("v").asString()).isEqualTo("second");
        // Two elements, two sub-runs: nothing is started after the last.
        verify(workflows, org.mockito.Mockito.times(2)).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFailedElementEndsTheTaskByDefaultAndKeepsWhatThereWas() {
        begin(List.of("a", "b", "c"), false);
        foreach.onElementDone(task(savedState), "success", result("{\"v\":1}"), null);

        foreach.onElementDone(task(savedState), "failure", null, "Sub-workflow 'x' ended in FAILED");

        TaskCompletedEvent done = published();
        assertThat(done.outcome()).isEqualTo("failure");
        assertThat(done.errorMessage()).contains("Element 1 failed").contains("FAILED");
        assertThat(done.output().size()).isEqualTo(2);
        assertThat(done.output().get(1).get("failed").asBoolean()).isTrue();
        // The third element never runs.
        verify(workflows, org.mockito.Mockito.times(2)).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void withContinueAFailedElementIsRecordedAndTheLoopGoesOn() {
        begin(List.of("a", "b"), true);

        foreach.onElementDone(task(savedState), "failure", null, "boom");
        verify(bus, never()).publish(any());
        foreach.onElementDone(task(savedState), "success", result("{\"v\":2}"), null);

        TaskCompletedEvent done = published();
        assertThat(done.outcome()).isEqualTo("success");
        assertThat(done.output().get(0).get("failed").asBoolean()).isTrue();
        assertThat(done.output().get(0).get("message").asString()).isEqualTo("boom");
        assertThat(done.output().get(1).get("v").asInt()).isEqualTo(2);
    }

    @Test
    void whenTheParentRunWasStoppedNothingFurtherStartsAndNothingIsPublished() {
        begin(List.of("a", "b"), false);
        when(workflows.currentStatus(any(), any(), any())).thenReturn(MagratheaRunStatus.TERMINATED);

        assertThat(foreach.onElementDone(task(savedState), "failure", null, "stopped"))
                .isTrue();

        verify(workflows, org.mockito.Mockito.times(1)).start(any(), any(), any(), any(), any(), any(), any());
        verify(bus, never()).publish(any());
    }

    @Test
    void ifTheNextElementWillNotStartTheTaskFailsWithTheResultsSoFar() {
        begin(List.of("a", "b"), false);
        when(workflows.start(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new MagratheaWorkflowService.MagratheaWorkflowException("lane busy"));

        foreach.onElementDone(task(savedState), "success", result("{\"v\":1}"), null);

        TaskCompletedEvent done = published();
        assertThat(done.outcome()).isEqualTo("failure");
        assertThat(done.errorMessage())
                .contains("Element 1 could not be started")
                .contains("lane busy");
        assertThat(done.output().size()).isEqualTo(1);
    }

    @Test
    void itemsKeepTheirStructureAcrossTheRoundTripThroughTheTaskRow() {
        // Keys with dots are legal in an item and illegal in a Mongo map: the state is text for that reason.
        Map<String, Object> odd = new LinkedHashMap<>();
        odd.put("a.b", 1);
        odd.put("nested", List.of(Map.of("x", true)));
        begin(List.of(odd, "second"), false);

        foreach.onElementDone(task(savedState), "success", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(workflows, org.mockito.Mockito.times(2))
                .start(any(), any(), any(), params.capture(), any(), any(), any());
        assertThat(params.getAllValues().get(0).get("item")).isEqualTo(odd);
    }

    // ──────────────────── helpers ────────────────────

    private void begin(List<?> items, boolean continueOnError) {
        foreach.begin(ctx(), "handle-one", Map.of(), items, "item", continueOnError, 100);
    }

    private TaskCompletedEvent published() {
        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.forClass(TaskCompletedEvent.class);
        verify(bus).publish(captor.capture());
        return captor.getValue();
    }

    private JsonNode result(String json) {
        return mapper.readTree(json);
    }

    private static MagratheaTaskDocument task(String foreachState) {
        return MagratheaTaskDocument.builder()
                .id("task-1")
                .tenantId("acme")
                .projectId("proj")
                .workflowRunId("r1")
                .workflowName("parent")
                .stateName("handle_each")
                .taskType(MagratheaTaskType.WORKFLOW_TASK)
                .foreachState(foreachState)
                .build();
    }

    private static MagratheaTaskContext ctx() {
        MagratheaStateSpec state = new MagratheaStateSpec(
                "handle_each",
                MagratheaTaskType.WORKFLOW_TASK,
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
        return new MagratheaTaskContext(
                "acme",
                "proj",
                "r1",
                "task-1",
                "alice",
                new ResolvedMagratheaWorkflow(
                        "parent",
                        "",
                        MagratheaWorkflowSource.PROJECT,
                        null,
                        null,
                        null,
                        null,
                        "start",
                        Map.of(),
                        Map.of(),
                        MagratheaBoundsSpec.empty(),
                        List.of(),
                        List.of()),
                state,
                Map.of(),
                Map.of(),
                null,
                null);
    }
}
