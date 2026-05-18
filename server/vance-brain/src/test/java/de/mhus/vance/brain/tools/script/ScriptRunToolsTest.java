package de.mhus.vance.brain.tools.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionInvocation;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.ScriptActionExecutor;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.toolpack.SpawnTool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptRunToolsTest {

    private final ToolInvocationContext ctx =
            new ToolInvocationContext("t1", "p1", "sess-1", "proc-1", "alice");

    // ──────────────────── script_run_doc ────────────────────

    @Test
    void doc_tool_routes_to_executor_with_DOCUMENT_source() {
        ScriptActionExecutor exec = mock(ScriptActionExecutor.class);
        when(exec.execute(any())).thenReturn(ActionResult.success(Map.of("ok", true)));
        ScriptRunDocTool tool = new ScriptRunDocTool(exec);

        Map<String, Object> out = tool.invoke(Map.of(
                "path", "scripts/x.js",
                "params", Map.of("a", 1),
                "timeoutSeconds", 15), ctx);

        ArgumentCaptor<ActionInvocation<TriggerAction.Script>> captor =
                ArgumentCaptor.forClass(ActionInvocation.class);
        verify(exec).execute(captor.capture());
        ActionInvocation<TriggerAction.Script> inv = captor.getValue();
        assertThat(inv.action().source()).isEqualTo(ScriptSource.DOCUMENT);
        assertThat(inv.action().path()).isEqualTo("scripts/x.js");
        assertThat(inv.action().dirName()).isNull();
        assertThat(inv.action().timeoutSeconds()).isEqualTo(15);
        assertThat(inv.action().params()).containsEntry("a", 1);
        assertThat(inv.triggerKind()).isEqualTo(TriggerKind.TOOL);
        assertThat(out).containsEntry("outcome", "success");
        assertThat(out).containsEntry("output", Map.of("ok", true));
    }

    @Test
    void doc_tool_rejects_missing_path() {
        ScriptRunDocTool tool = new ScriptRunDocTool(mock(ScriptActionExecutor.class));

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("path");
    }

    @Test
    void doc_tool_translates_failure_outcome() {
        ScriptActionExecutor exec = mock(ScriptActionExecutor.class);
        when(exec.execute(any())).thenReturn(ActionResult.failure(
                ActionOutcome.BUSINESS_ERROR, "bad input", Map.of("error", "bad input")));
        ScriptRunDocTool tool = new ScriptRunDocTool(exec);

        Map<String, Object> out = tool.invoke(Map.of("path", "x.js"), ctx);

        assertThat(out).containsEntry("outcome", "business_error");
        assertThat(out).containsEntry("error", "bad input");
    }

    // ──────────────────── script_run_workspace ────────────────────

    @Test
    void workspace_tool_routes_with_WORKSPACE_source_and_dirName() {
        ScriptActionExecutor exec = mock(ScriptActionExecutor.class);
        when(exec.execute(any())).thenReturn(ActionResult.success(Map.of()));
        ScriptRunWorkspaceTool tool = new ScriptRunWorkspaceTool(exec);

        tool.invoke(Map.of(
                "dirName", "scratch",
                "path", "gen/x.js"), ctx);

        ArgumentCaptor<ActionInvocation<TriggerAction.Script>> captor =
                ArgumentCaptor.forClass(ActionInvocation.class);
        verify(exec).execute(captor.capture());
        TriggerAction.Script action = captor.getValue().action();
        assertThat(action.source()).isEqualTo(ScriptSource.WORKSPACE);
        assertThat(action.dirName()).isEqualTo("scratch");
        assertThat(action.path()).isEqualTo("gen/x.js");
    }

    @Test
    void workspace_tool_rejects_missing_dirName() {
        ScriptRunWorkspaceTool tool = new ScriptRunWorkspaceTool(mock(ScriptActionExecutor.class));

        assertThatThrownBy(() -> tool.invoke(Map.of("path", "x.js"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("dirName");
    }

    @Test
    void workspace_tool_rejects_missing_path() {
        ScriptRunWorkspaceTool tool = new ScriptRunWorkspaceTool(mock(ScriptActionExecutor.class));

        assertThatThrownBy(() -> tool.invoke(Map.of("dirName", "scratch"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("path");
    }

    // ──────────────────── SpawnTool annotation ────────────────────

    @Test
    void both_tools_are_annotated_as_SpawnTool() {
        assertThat(ScriptRunDocTool.class.isAnnotationPresent(SpawnTool.class)).isTrue();
        assertThat(ScriptRunWorkspaceTool.class.isAnnotationPresent(SpawnTool.class)).isTrue();
    }
}
