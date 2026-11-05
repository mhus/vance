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
    private final de.mhus.vance.shared.magrathea.MagratheaTaskService taskService =
            mock(de.mhus.vance.shared.magrathea.MagratheaTaskService.class);
    private final ToolTaskExecutor executor = new ToolTaskExecutor(dispatcher, objectMapper, taskService);

    @Test
    void successful_invocation_returns_success_with_result_as_output() {
        when(dispatcher.invoke(eq("github.merge_pr"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("merged", true, "sha", "abc123"));

        Optional<TaskOutcome> outcome =
                executor.execute(ctx(toolState("github.merge_pr", Map.of("url", "https://x/y/pull/1"))));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.get().output().get("merged").asBoolean()).isTrue();
        assertThat(outcome.get().output().get("sha").asString()).isEqualTo("abc123");
    }

    @Test
    void params_block_is_forwarded_verbatim() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class))).thenReturn(Map.of());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", "https://x/y/pull/1");
        params.put("dry_run", true);
        executor.execute(ctx(toolState("github.merge_pr", params)));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.<Map<String, Object>>captor();
        org.mockito.Mockito.verify(dispatcher)
                .invoke(eq("github.merge_pr"), captor.capture(), any(ToolInvocationContext.class));
        assertThat(captor.getValue()).containsEntry("url", "https://x/y/pull/1").containsEntry("dry_run", true);
    }

    @Test
    void invocation_context_carries_tenant_project_and_startedBy() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class))).thenReturn(Map.of());

        executor.execute(ctx(toolState("github.merge_pr", Map.of())));

        ArgumentCaptor<ToolInvocationContext> captor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(dispatcher).invoke(any(), any(), captor.capture());
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

        Optional<TaskOutcome> outcome = executor.execute(ctx(toolState("github.merge_pr", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo("permission_error");
    }

    @Test
    void engineRolePrivilegedTool_isRefused_withoutInvoking() {
        // A workflow tool_task must not reach an engine-role-privileged tool
        // (cross_process_create, user_*, tool_health_*, …) — that would
        // bypass the role gate (code-review F1).
        de.mhus.vance.toolpack.Tool gated = mock(de.mhus.vance.toolpack.Tool.class);
        when(gated.requiresEngineRoles()).thenReturn(java.util.Set.of("trillian-user"));
        when(dispatcher.resolve(eq("cross_process_create"), any(ToolInvocationContext.class)))
                .thenReturn(Optional.of(new ToolDispatcher.Resolved(gated, null)));

        Optional<TaskOutcome> outcome = executor.execute(ctx(toolState("cross_process_create", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo("permission_error");
        org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.never())
                .invoke(any(), any(), any(ToolInvocationContext.class));
    }

    @Test
    void ToolException_yields_technical_error() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new ToolException("upstream timeout"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(toolState("github.merge_pr", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo("technical_error");
        assertThat(outcome.get().errorMessage()).isEqualTo("upstream timeout");
    }

    @Test
    void runtime_exception_yields_technical_error() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class))).thenThrow(new RuntimeException("boom"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(toolState("github.merge_pr", Map.of())));

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

        Optional<TaskOutcome> outcome = executor.execute(ctx(toolState("noop", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
        assertThat(outcome.get().output()).isNull();
    }

    @Test
    void a_rest_result_that_says_429_is_rate_limited_not_a_success_and_keeps_its_body() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of(
                        "ok", false, "status", 429, "headers", Map.of("Retry-After", "30"), "body", "slow down"));

        TaskOutcome outcome =
                executor.execute(ctx(toolState("slack.post", Map.of()))).get();

        assertThat(outcome.outcome()).isEqualTo("rate_limited");
        assertThat(outcome.output().get("body").asString()).isEqualTo("slow down");
        // The retry waits for what the service asked.
        ArgumentCaptor<java.time.Instant> asked = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(taskService).setRetryNotBefore(eq("task-1"), asked.capture());
        assertThat(asked.getValue()).isAfter(java.time.Instant.now().plusSeconds(20));
    }

    @Test
    void a_rest_result_that_says_401_is_an_auth_error() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", false, "status", 401));

        assertThat(executor.execute(ctx(toolState("slack.post", Map.of())))
                        .get()
                        .outcome())
                .isEqualTo("auth_error");
    }

    @Test
    void a_rest_result_that_says_ok_is_still_a_success() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 200));

        assertThat(executor.execute(ctx(toolState("slack.post", Map.of())))
                        .get()
                        .outcome())
                .isEqualTo(TaskCompletedEvent.OUTCOME_SUCCESS);
    }

    @Test
    void an_mcp_server_that_answers_503_is_unavailable() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new de.mhus.vance.toolpack.mcp.McpHttpStatusException("MCP HTTP 503", 503, null));

        assertThat(executor.execute(ctx(toolState("mcp.call", Map.of()))).get().outcome())
                .isEqualTo("unavailable");
    }

    @Test
    void a_network_failure_of_a_rest_pack_is_unavailable() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new de.mhus.vance.toolpack.rest.RestHttpInvoker.RestInvocationException(
                        "connect failed", new java.net.ConnectException("refused")));

        assertThat(executor.execute(ctx(toolState("slack.post", Map.of())))
                        .get()
                        .outcome())
                .isEqualTo("unavailable");
    }

    @Test
    void a_status_is_found_even_when_the_failure_is_wrapped() {
        when(dispatcher.invoke(any(), any(), any(ToolInvocationContext.class)))
                .thenThrow(new RuntimeException(
                        "tool failed", new de.mhus.vance.toolpack.mcp.McpHttpStatusException("gone", 404, null)));

        assertThat(executor.execute(ctx(toolState("mcp.call", Map.of()))).get().outcome())
                .isEqualTo("not_found");
    }

    @Test
    void the_status_table_sorts_by_what_to_do() {
        assertThat(ToolFailure.kindForStatus(200)).isNull();
        assertThat(ToolFailure.kindForStatus(400))
                .isEqualTo(de.mhus.vance.api.magrathea.MagratheaErrorKind.BUSINESS_ERROR);
        assertThat(ToolFailure.kindForStatus(403))
                .isEqualTo(de.mhus.vance.api.magrathea.MagratheaErrorKind.PERMISSION_ERROR);
        assertThat(ToolFailure.kindForStatus(500))
                .isEqualTo(de.mhus.vance.api.magrathea.MagratheaErrorKind.TECHNICAL_ERROR);
        assertThat(ToolFailure.kindForStatus(502))
                .isEqualTo(de.mhus.vance.api.magrathea.MagratheaErrorKind.UNAVAILABLE);
    }

    // ──────── helpers ────────

    private static MagratheaStateSpec toolState(String tool, Map<String, Object> params) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (tool != null) spec.put("tool", tool);
        spec.put("params", params);
        return new MagratheaStateSpec(
                "call",
                MagratheaTaskType.TOOL_TASK,
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
                        "noop",
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
