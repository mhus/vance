package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The {@code vance.workflow} surface — start, status, current. */
class VanceScriptApiWorkflowTest {

    private static ContextToolsApi tools() {
        ContextToolsApi tools = mock(ContextToolsApi.class);
        when(tools.scope()).thenReturn(new ToolInvocationContext("t", "p", "s", "proc", "u"));
        return tools;
    }

    private static VanceScriptApi apiWith(ContextToolsApi tools,
                                          @org.jspecify.annotations.Nullable
                                                  ScriptWorkflowHost host) {
        return new VanceScriptApi(tools, null, Set.of(), null, null, null, null,
                null, null, null, null, null, null, host);
    }

    private static MagratheaProcessDto dto(String tenantId, String projectId) {
        return MagratheaProcessDto.builder()
                .workflowRunId("run-1")
                .workflowName("release")
                .tenantId(tenantId)
                .projectId(projectId)
                .status(MagratheaRunStatus.RUNNING)
                .currentState("build")
                .vars(Map.of("version", "1.0.0"))
                .createdAt(Instant.parse("2026-08-14T10:00:00Z"))
                .build();
    }

    // ──────────────────── start ────────────────────

    @Test
    void start_delegatesTo_workflowStartTool() {
        ContextToolsApi tools = tools();
        when(tools.invoke(eq("workflow_start"), any()))
                .thenReturn(Map.of("workflowRunId", "run-9", "workflowName", "helloworld"));
        VanceScriptApi api = apiWith(tools, null);

        Map<String, Object> res = api.workflow.start(Map.of("path", "workflows/helloworld.yaml"));

        assertThat(res).containsEntry("workflowRunId", "run-9");
        verify(tools).invoke("workflow_start", Map.of("path", "workflows/helloworld.yaml"));
    }

    @Test
    void start_isRefused_whenSpawnToolsAreDenied() {
        // Trigger-scoped runs deny every @SpawnTool; the wrapper must not
        // route around that — it is the whole reason start goes through
        // the tool bus instead of the service.
        ContextToolsApi tools = tools();
        VanceScriptApi api = new VanceScriptApi(tools, null, Set.of("workflow_start"));

        assertThatThrownBy(() -> api.workflow.start(Map.of("name", "release")))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("not allowed in trigger-scoped script");
        verify(tools, never()).invoke(eq("workflow_start"), any());
    }

    // ──────────────────── status ────────────────────

    @Test
    void status_projectsRun_ofOwnScope() {
        MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
        when(projector.project("t", "p", "run-1")).thenReturn(Optional.of(dto("t", "p")));
        VanceScriptApi api = apiWith(tools(), ScriptWorkflowHost.of(projector));

        Map<String, Object> status = api.workflow.status("run-1");

        assertThat(status)
                .containsEntry("workflowRunId", "run-1")
                .containsEntry("status", "RUNNING")
                .containsEntry("currentState", "build")
                .containsEntry("createdAt", "2026-08-14T10:00:00Z");
        assertThat(status).extracting("vars").isEqualTo(Map.of("version", "1.0.0"));
    }

    @Test
    void status_returnsNull_forUnknownRun() {
        MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
        when(projector.project(any(), any(), any())).thenReturn(Optional.empty());
        VanceScriptApi api = apiWith(tools(), ScriptWorkflowHost.of(projector));

        assertThat(api.workflow.status("nope")).isNull();
    }

    @Test
    void status_returnsNull_forRunOfAnotherProject() {
        // Same shape as "unknown" on purpose — a foreign run must not be
        // distinguishable from a missing one.
        MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
        when(projector.project("t", "p", "run-1")).thenReturn(Optional.of(dto("t", "other")));
        VanceScriptApi api = apiWith(tools(), ScriptWorkflowHost.of(projector));

        assertThat(api.workflow.status("run-1")).isNull();
    }

    @Test
    void status_throws_whenMagratheaIsDisabled() {
        VanceScriptApi api = apiWith(tools(), ScriptWorkflowHost.of(null));

        assertThatThrownBy(() -> api.workflow.status("run-1"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("vance.services.magrathea");
    }

    @Test
    void status_throws_onBlankRunId() {
        MagratheaStateProjector projector = mock(MagratheaStateProjector.class);
        VanceScriptApi api = apiWith(tools(), ScriptWorkflowHost.of(projector));

        assertThatThrownBy(() -> api.workflow.status("  "))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("must not be empty");
        verify(projector, never()).project(any(), any(), any());
    }

    // ──────────────────── current ────────────────────

    @Test
    void current_isNull_outsideAWorkflowTask() {
        assertThat(apiWith(tools(), null).workflow.current).isNull();
        assertThat(new VanceScriptApi(tools(), null, Set.of()).workflow.current).isNull();
    }

    @Test
    void current_exposesRunIdentityParamsAndVars() {
        ScriptWorkflowRun run = new ScriptWorkflowRun(
                "run-7", "release", "build", "task-3", "mara",
                Map.of("version", "1.0.0"), Map.of("sha", "abc"));
        VanceScriptApi api = apiWith(tools(), new ScriptWorkflowHost(null, run));

        assertThat(api.workflow.current)
                .containsEntry("runId", "run-7")
                .containsEntry("workflowName", "release")
                .containsEntry("state", "build")
                .containsEntry("taskId", "task-3")
                .containsEntry("startedBy", "mara")
                .containsEntry("params", Map.of("version", "1.0.0"))
                .containsEntry("vars", Map.of("sha", "abc"));
    }

    @Test
    void current_isReadOnly_soAScriptCannotForgeAJournalWrite() {
        ScriptWorkflowRun run = new ScriptWorkflowRun(
                "run-7", "release", "build", "task-3", null,
                new java.util.LinkedHashMap<>(Map.of("a", 1)),
                new java.util.LinkedHashMap<>(Map.of("b", 2)));
        VanceScriptApi api = apiWith(tools(), new ScriptWorkflowHost(null, run));

        assertThatThrownBy(() -> api.workflow.current.put("runId", "hacked"))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) api.workflow.current.get("vars");
        assertThatThrownBy(() -> vars.put("b", 99))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
