package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowTaskExecutorTest {

    private final MagratheaWorkflowService workflowService = mock(MagratheaWorkflowService.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaTimeoutScheduler timeoutScheduler = mock(MagratheaTimeoutScheduler.class);
    private final MagratheaForeach foreach = mock(MagratheaForeach.class);
    private final WorkflowTaskExecutor executor =
            new WorkflowTaskExecutor(workflowService, taskService, timeoutScheduler, foreach);

    @Test
    void happy_path_spawns_sub_workflow_and_returns_async() {
        when(workflowService.start(
                        eq("acme"), eq("proj"), eq("build-and-test"), any(), any(), eq("r1"), eq("build_subprojects")))
                .thenReturn("sub-run-id");

        Optional<TaskOutcome> outcome = executor.execute(ctx(workflowState("build-and-test", Map.of("repo", "x"))));

        assertThat(outcome).isEmpty();
        verify(taskService).linkSubWorkflow("task-1", "sub-run-id");
    }

    @Test
    void missing_workflow_field_fails_synchronously() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(workflowState(null, Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("'workflow:'");
        verify(taskService, never()).linkSubWorkflow(any(), any());
    }

    @Test
    void sub_workflow_not_found_propagates_as_failure() {
        when(workflowService.start(any(), any(), eq("ghost"), any(), any(), any(), any()))
                .thenThrow(new MagratheaWorkflowService.MagratheaWorkflowException(
                        "Workflow 'ghost' not found in cascade for tenant=acme project=proj"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(workflowState("ghost", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("not found");
    }

    @Test
    void parent_identity_threaded_into_start() {
        when(workflowService.start(
                        eq("acme"), eq("proj"), eq("sub"), any(), eq("alice"), eq("r1"), eq("build_subprojects")))
                .thenReturn("sub-id");

        executor.execute(ctx(workflowState("sub", Map.of())));

        // The arg-matchers above are the assertion — Mockito would
        // throw on a mismatch. A successful execute = the parent
        // identity was threaded through.
        verify(workflowService)
                .start(eq("acme"), eq("proj"), eq("sub"), any(), eq("alice"), eq("r1"), eq("build_subprojects"));
    }

    @Test
    void params_forwarded_verbatim_to_sub_run() {
        when(workflowService.start(any(), any(), eq("sub"), any(), any(), any(), any()))
                .thenReturn("sub-id");

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("a", 1);
        p.put("b", "x");
        executor.execute(ctx(workflowState("sub", p)));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.captor();
        verify(workflowService).start(any(), any(), eq("sub"), captor.capture(), any(), any(), any());
        assertThat(captor.getValue()).containsEntry("a", 1).containsEntry("b", "x");
    }

    // ──────────────────── items: (for-each) ────────────────────

    @Test
    void items_hands_the_loop_to_the_foreach_and_waits_when_it_started() {
        when(foreach.begin(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());

        Optional<TaskOutcome> outcome = executor.execute(ctx(foreachState(
                Map.of("items", List.of("a", "b"), "as", "ticket", "onItemError", "continue", "maxItems", 7))));

        assertThat(outcome).isEmpty();
        verify(foreach)
                .begin(
                        any(),
                        eq("handle-one"),
                        eq(Map.of("channel", "#ops")),
                        eq(List.of("a", "b")),
                        eq("ticket"),
                        eq(true),
                        eq(7));
        verify(timeoutScheduler).arm(any(), any());
        // The element sub-runs are the foreach's to start, not the executor's.
        verify(workflowService, never()).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void items_defaults_are_item_fail_and_a_hundred() {
        when(foreach.begin(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());

        executor.execute(ctx(foreachState(Map.of("items", List.of("a")))));

        verify(foreach)
                .begin(any(), any(), any(), any(), eq("item"), eq(false), eq(MagratheaForeach.DEFAULT_MAX_ITEMS));
    }

    @Test
    void items_that_end_at_once_are_returned_and_arm_no_timeout() {
        when(foreach.begin(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.of(TaskOutcome.failure("items must be a list")));

        Optional<TaskOutcome> outcome = executor.execute(ctx(foreachState(Map.of("items", "text"))));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        verify(timeoutScheduler, never()).arm(any(), any());
    }

    @Test
    void an_unknown_onItemError_is_refused_before_anything_starts() {
        Optional<TaskOutcome> outcome =
                executor.execute(ctx(foreachState(Map.of("items", List.of("a"), "onItemError", "ignore"))));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("onItemError");
        verify(foreach, never())
                .begin(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void maxItems_outside_one_to_the_hard_limit_is_refused() {
        for (Object bad : List.<Object>of(0, -3, MagratheaForeach.HARD_MAX_ITEMS + 1, "many")) {
            Optional<TaskOutcome> outcome =
                    executor.execute(ctx(foreachState(Map.of("items", List.of("a"), "maxItems", bad))));
            assertThat(outcome.get().outcome()).as("maxItems=%s", bad).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
            assertThat(outcome.get().errorMessage()).contains("maxItems");
        }
    }

    private static MagratheaStateSpec foreachState(Map<String, Object> extra) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("workflow", "handle-one");
        spec.put("params", Map.of("channel", "#ops"));
        spec.putAll(extra);
        return new MagratheaStateSpec(
                "handle_each",
                MagratheaTaskType.WORKFLOW_TASK,
                null,
                null,
                null,
                null,
                java.util.List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaStateSpec workflowState(
            @org.jspecify.annotations.Nullable String subWorkflow, Map<String, Object> params) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (subWorkflow != null) spec.put("workflow", subWorkflow);
        spec.put("params", params);
        return new MagratheaStateSpec(
                "build_subprojects",
                MagratheaTaskType.WORKFLOW_TASK,
                null,
                null,
                null,
                null,
                java.util.List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state) {
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
