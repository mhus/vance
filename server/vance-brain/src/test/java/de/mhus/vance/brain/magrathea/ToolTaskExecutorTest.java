package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ToolTaskExecutorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ToolTaskExecutor executor = new ToolTaskExecutor(dispatcher, objectMapper);

    @Test
    void successful_invocation_returns_success_with_result_as_output() {
        when(dispatcher.invoke(eq("github.merge_pr"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("merged", true, "sha", "abc123"));

        Optional<TaskOutcome> outcome = executor.execute(
                ctx(toolState("github.merge_pr", Map.of("url", "https://x/y/pull/1"))));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.get().output().get("merged").asBoolean()).isTrue();
        assertThat(outcome.get().output().get("sha").asString()).isEqualTo("abc123");
    }

    @Test
    void params_block_is_forwarded_verbatim() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", "https://x/y/pull/1");
        params.put("dry_run", true);
        executor.execute(ctx(toolState("github.merge_pr", params)));

        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.<Map<String, Object>>captor();
        org.mockito.Mockito.verify(dispatcher)
                .invoke(eq("github.merge_pr"), captor.capture(), any(ToolInvocationContext.class));
        assertThat(captor.getValue()).containsEntry("url", "https://x/y/pull/1")
                .containsEntry("dry_run", true);
    }

    @Test
    void invocation_context_carries_tenant_project_and_startedBy() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of());

        executor.execute(ctx(toolState("github.merge_pr", Map.of())));

        ArgumentCaptor<ToolInvocationContext> captor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(dispatcher)
                .invoke(any(), any(), captor.capture());
        ToolInvocationContext invoked = captor.getValue();
        assertThat(invoked.tenantId()).isEqualTo("acme");
        assertThat(invoked.projectId()).isEqualTo("proj");
        assertThat(invoked.userId()).isEqualTo("alice");
        assertThat(invoked.sessionId()).isNull();
        assertThat(invoked.processId()).isNull();
    }

    @Test
    void PermissionDeniedException_yields_permission_error() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new PermissionDeniedException(
                        SecurityContext.user("alice", "acme", List.of()),
                        new Resource.Project("acme", "proj"),
                        Action.EXECUTE));

        Optional<TaskOutcome> outcome = executor.execute(
                ctx(toolState("github.merge_pr", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo("permission_error");
    }

    @Test
    void ToolException_yields_technical_error() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new ToolException("upstream timeout"));

        Optional<TaskOutcome> outcome = executor.execute(
                ctx(toolState("github.merge_pr", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo("technical_error");
        assertThat(outcome.get().errorMessage()).isEqualTo("upstream timeout");
    }

    @Test
    void runtime_exception_yields_technical_error() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new RuntimeException("boom"));

        Optional<TaskOutcome> outcome = executor.execute(
                ctx(toolState("github.merge_pr", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo("technical_error");
    }

    @Test
    void missing_tool_field_fails_at_executor() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(toolState(null, Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("'tool:'");
    }

    @Test
    void null_tool_result_yields_success_with_null_output() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class))).thenReturn(null);

        Optional<TaskOutcome> outcome = executor.execute(
                ctx(toolState("noop", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.get().output()).isNull();
    }

    // ──────── helpers ────────

    private static MagratheaStateSpec toolState(String tool, Map<String, Object> params) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (tool != null) spec.put("tool", tool);
        spec.put("params", params);
        return new MagratheaStateSpec(
                "call",
                MagratheaTaskType.TOOL_TASK,
                null, null, null,
                Map.of(), Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state) {
        return new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedMagratheaWorkflow("noop", "", MagratheaWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), MagratheaBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
