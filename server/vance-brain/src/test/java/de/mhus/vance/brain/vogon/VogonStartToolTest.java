package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link VogonStartTool}: the F4 gate (a live run refuses a new start) and
 * the F2 Weg-A delegation (the engine's start path runs, intake errors come
 * back as the tool's result instead of closing the process).
 */
class VogonStartToolTest {

    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final VogonEngine engine = mock(VogonEngine.class);
    private final VogonStartTool tool = new VogonStartTool(processes, engine);

    private ThinkProcessDocument process;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        process = new ThinkProcessDocument();
        process.setId("vogon-1");
        process.setTenantId("t");
        process.setProjectId("p");
        process.setSessionId("s");
        ctx = mock(ToolInvocationContext.class);
        when(ctx.processId()).thenReturn("vogon-1");
        when(processes.findById("vogon-1")).thenReturn(Optional.of(process));
    }

    @Test
    void aLiveRunRefusesTheStart() {
        when(engine.runIsLive(process)).thenReturn(true);

        assertThatThrownBy(() -> tool.invoke(Map.of(VogonEngine.PARAM_WORKFLOW, "release"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("vogon_stop");

        verify(engine, never()).startPlan(any(), any());
    }

    @Test
    void aTerminalRunAllowsTheRestart() throws Exception {
        when(engine.runIsLive(process)).thenReturn(false);
        when(engine.startPlan(any(), eq("release version 1.2.3"))).thenReturn("run-9");

        Map<String, Object> out =
                tool.invoke(Map.of(VogonEngine.PARAM_WORKFLOW, "release", "task", "release version 1.2.3"), ctx);

        assertThat(out).containsEntry("started", true).containsEntry("workflowRunId", "run-9");
    }

    @Test
    void anIntakeFailureComesBackAsAToolResultNotAClose() {
        when(engine.runIsLive(process)).thenReturn(false);
        when(engine.startPlan(any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "Vogon cannot resolve the plan it was given: " + "workflow='relase' — no such plan here"));

        assertThatThrownBy(() -> tool.invoke(Map.of(VogonEngine.PARAM_WORKFLOW, "relase"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("relase");

        verify(processes, never()).closeProcess(any(), any());
    }

    @Test
    void workflowAndPathAtOnceAreRefused() {
        assertThatThrownBy(() -> tool.invoke(
                        Map.of(
                                VogonEngine.PARAM_WORKFLOW, "release",
                                VogonEngine.PARAM_WORKFLOW_PATH, "plans/release.yaml"),
                        ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("exactly one");

        verify(engine, never()).startPlan(any(), any());
    }

    @Test
    void thePlanReferenceIsPersistedAndItsAlternativeCleared() throws Exception {
        when(engine.runIsLive(process)).thenReturn(false);
        when(engine.startPlan(any(), any())).thenReturn("run-9");
        process.setEngineParams(new LinkedHashMap<>(
                Map.of(VogonEngine.PARAM_WORKFLOW_PATH, "plans/release.yaml", VogonEngine.PARAM_SESSION_MODE, true)));

        tool.invoke(Map.of(VogonEngine.PARAM_WORKFLOW, "release"), ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> persisted = ArgumentCaptor.forClass(Map.class);
        verify(processes).replaceEngineParams(eq("vogon-1"), persisted.capture());
        assertThat(persisted.getValue())
                .containsEntry(VogonEngine.PARAM_WORKFLOW, "release")
                .doesNotContainKey(VogonEngine.PARAM_WORKFLOW_PATH)
                // The stale terminal run stays readable until the new one replaces it.
                .containsKey(VogonEngine.PARAM_SESSION_MODE);
    }

    @Test
    void controlKeysNeverBecomePlanParameters() throws Exception {
        when(engine.runIsLive(process)).thenReturn(false);
        when(engine.startPlan(any(), any())).thenReturn("run-9");
        process.setEngineParams(new LinkedHashMap<>());

        tool.invoke(
                Map.of(
                        VogonEngine.PARAM_WORKFLOW,
                        "release",
                        "params",
                        Map.of(
                                "channel",
                                "beta",
                                VogonEngine.PARAM_RUN_ID,
                                "smuggled",
                                VogonEngine.PARAM_SESSION_MODE,
                                "smuggled")),
                ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> persisted = ArgumentCaptor.forClass(Map.class);
        verify(processes).replaceEngineParams(eq("vogon-1"), persisted.capture());
        assertThat(persisted.getValue()).containsEntry("channel", "beta").doesNotContainKey("workflowRunId");
    }
}
