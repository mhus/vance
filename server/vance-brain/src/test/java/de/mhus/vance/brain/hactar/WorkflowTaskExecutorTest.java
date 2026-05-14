package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.api.hactar.HactarWorkflowSource;
import de.mhus.vance.shared.hactar.HactarBoundsSpec;
import de.mhus.vance.shared.hactar.HactarRetrySpec;
import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.hactar.HactarTaskService;
import de.mhus.vance.shared.hactar.ResolvedHactarWorkflow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowTaskExecutorTest {

    private final HactarWorkflowService workflowService = mock(HactarWorkflowService.class);
    private final HactarTaskService taskService = mock(HactarTaskService.class);
    private final WorkflowTaskExecutor executor =
            new WorkflowTaskExecutor(workflowService, taskService);

    @Test
    void happy_path_spawns_sub_workflow_and_returns_async() {
        when(workflowService.start(
                eq("acme"), eq("proj"), eq("build-and-test"), any(), any(),
                eq("r1"), eq("build_subprojects")))
                .thenReturn("sub-run-id");

        Optional<TaskOutcome> outcome = executor.execute(ctx(workflowState(
                "build-and-test", Map.of("repo", "x"))));

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
                .thenThrow(new HactarWorkflowService.HactarWorkflowException(
                        "Workflow 'ghost' not found in cascade for tenant=acme project=proj"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(workflowState("ghost", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("not found");
    }

    @Test
    void parent_identity_threaded_into_start() {
        when(workflowService.start(
                eq("acme"), eq("proj"), eq("sub"), any(), eq("alice"),
                eq("r1"), eq("build_subprojects")))
                .thenReturn("sub-id");

        executor.execute(ctx(workflowState("sub", Map.of())));

        // The arg-matchers above are the assertion — Mockito would
        // throw on a mismatch. A successful execute = the parent
        // identity was threaded through.
        verify(workflowService).start(
                eq("acme"), eq("proj"), eq("sub"), any(), eq("alice"),
                eq("r1"), eq("build_subprojects"));
    }

    @Test
    void params_forwarded_verbatim_to_sub_run() {
        when(workflowService.start(
                any(), any(), eq("sub"), any(), any(), any(), any()))
                .thenReturn("sub-id");

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("a", 1);
        p.put("b", "x");
        executor.execute(ctx(workflowState("sub", p)));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.captor();
        verify(workflowService).start(
                any(), any(), eq("sub"), captor.capture(), any(), any(), any());
        assertThat(captor.getValue()).containsEntry("a", 1).containsEntry("b", "x");
    }

    private static HactarStateSpec workflowState(
            @org.jspecify.annotations.Nullable String subWorkflow,
            Map<String, Object> params) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (subWorkflow != null) spec.put("workflow", subWorkflow);
        spec.put("params", params);
        return new HactarStateSpec(
                "build_subprojects",
                HactarTaskType.WORKFLOW_TASK,
                null, null, null,
                Map.of(), Map.of(),
                List.of(),
                HactarRetrySpec.none(),
                spec);
    }

    private static HactarTaskContext ctx(HactarStateSpec state) {
        return new HactarTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedHactarWorkflow("parent", "", HactarWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), HactarBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
