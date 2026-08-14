package de.mhus.vance.brain.tools.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowParseException;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowStartToolTest {

    private final MagratheaWorkflowService workflowService = mock(MagratheaWorkflowService.class);
    private final WorkflowStartTool tool = new WorkflowStartTool(workflowService);

    @Test
    void happy_path_returns_workflowRunId_and_name() {
        when(workflowService.start(
                eq("acme"), eq("proj"), eq("demo"), any(), eq("alice")))
                .thenReturn("ab12cd34");

        Map<String, Object> result = tool.invoke(
                Map.of("name", "demo", "params", Map.of("k", "v")),
                ctx("acme", "proj", "alice"));

        assertThat(result).containsEntry("workflowRunId", "ab12cd34")
                          .containsEntry("workflowName", "demo");
    }

    @Test
    void params_block_is_forwarded() {
        when(workflowService.start(any(), any(), any(), any(), any())).thenReturn("ab");

        tool.invoke(Map.of("name", "demo", "params", Map.of("k", "v")),
                ctx("acme", "proj", "alice"));

        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.<Map<String, Object>>captor();
        verify(workflowService).start(any(), any(), eq("demo"), captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("k", "v");
    }

    @Test
    void missing_project_in_context_fails() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("name", "demo"),
                new ToolInvocationContext("acme", null, null, null, "alice")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("project context");
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void neither_name_nor_path_fails() {
        // 'name' alone stopped being required when 'path' arrived: a plan is
        // addressed one way or the other, and the error has to name both.
        assertThatThrownBy(() -> tool.invoke(
                Map.of("params", Map.of()),
                ctx("acme", "proj", "alice")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("path");
    }

    @Test
    void non_map_params_field_fails() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("name", "demo", "params", "not-a-map"),
                ctx("acme", "proj", "alice")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("must be an object");
    }

    @Test
    void unknown_workflow_propagates_as_ToolException() {
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenThrow(new MagratheaWorkflowService.MagratheaWorkflowException(
                        "Workflow 'ghost' not found in cascade for tenant=acme project=proj"));

        assertThatThrownBy(() -> tool.invoke(
                Map.of("name", "ghost"),
                ctx("acme", "proj", "alice")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void yaml_parse_failure_propagates_as_ToolException() {
        when(workflowService.start(any(), any(), any(), any(), any()))
                .thenThrow(new MagratheaWorkflowParseException("bad shape"));

        assertThatThrownBy(() -> tool.invoke(
                Map.of("name", "broken"),
                ctx("acme", "proj", "alice")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("YAML invalid");
    }

    @Test
    void tool_metadata_is_correct() {
        assertThat(tool.name()).isEqualTo("workflow_start");
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.primary()).isFalse();
        assertThat(tool.labels()).contains("workflow").contains("side-effect");
    }

    // ──────────── addressing a plan by path ────────────
    //
    // The path form exists because its absence had a visible cost: an agent
    // told "workflows live under _vance/workflows/" and holding a plan
    // elsewhere starts copying the file to make the tool work.

    @Test
    void by_path_starts_that_document() {
        when(workflowService.startFromDocument(
                eq("acme"), eq("proj"), eq("workflows/hello.yaml"), any(), eq("alice")))
                .thenReturn("run-2");

        Map<String, Object> result = tool.invoke(
                Map.of("path", "workflows/hello.yaml"),
                ctx("acme", "proj", "alice"));

        assertThat(result).containsEntry("workflowRunId", "run-2")
                          .containsEntry("workflowPath", "workflows/hello.yaml")
                          // The stem still names the run, so listings group.
                          .containsEntry("workflowName", "hello");
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void a_path_passed_as_name_is_understood_anyway() {
        // Refusing it would be pedantry — the value already says how it wants
        // to be resolved, and the workaround an agent reaches for is copying.
        when(workflowService.startFromDocument(
                any(), any(), eq("workflows/hello.yaml"), any(), any()))
                .thenReturn("run-3");

        Map<String, Object> result = tool.invoke(
                Map.of("name", "workflows/hello.yaml"),
                ctx("acme", "proj", "alice"));

        assertThat(result).containsEntry("workflowRunId", "run-3");
        verify(workflowService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void both_name_and_path_is_rejected_rather_than_silently_preferring_one() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("name", "demo", "path", "workflows/other.yaml"),
                ctx("acme", "proj", "alice")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void params_are_forwarded_on_the_path_route_too() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
        when(workflowService.startFromDocument(any(), any(), any(), captor.capture(), any()))
                .thenReturn("run-4");

        tool.invoke(Map.of("path", "workflows/hello.yaml", "params", Map.of("k", "v")),
                ctx("acme", "proj", "alice"));

        assertThat(captor.getValue()).containsEntry("k", "v");
    }

    private static ToolInvocationContext ctx(String tenant, String project, String user) {
        return new ToolInvocationContext(tenant, project, null, null, user);
    }
}
