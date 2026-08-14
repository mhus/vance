package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code vance.workflow} as JavaScript actually sees it — the
 * {@link VanceScriptApiWorkflowTest} unit tests call the Java methods
 * directly and would still pass if the members were not exported to the
 * guest, which is the mistake that costs a debugging session.
 */
class ScriptWorkflowSurfaceJsTest {

    private static Engine engine;
    private static ScriptExecutor executor;

    @BeforeAll
    static void start() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        executor = new GraaljsScriptExecutor(engine);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static ContextToolsApi tools() {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.<Tool>of());
        when(src.find(any(), any())).thenReturn(Optional.empty());
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src),
                new PermissionService(List.of(new RecordingPermissionResolver())),
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(de.mhus.vance.shared.team.TeamService.class));
        return new ContextToolsApi(dispatcher,
                new ToolInvocationContext("acme", "proj-1", "sess-1", "proc-1", "alice"),
                Set.of());
    }

    private static ScriptRequest request(String code) {
        return new ScriptRequest("js", code, "test", tools(), Duration.ofSeconds(5), Map.of());
    }

    private static ScriptWorkflowRun run() {
        return new ScriptWorkflowRun("run-7", "release", "build", "task-3", "mara",
                Map.of("version", "1.0.0"), Map.of("sha", "abc"));
    }

    @Test
    void current_isNull_forAnOrdinaryScriptRun() {
        assertThat(executor.run(request("vance.workflow.current === null")).value())
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void current_readsAsAPlainObject_insideAWorkflowTask() {
        ScriptRequest req = request(
                "vance.workflow.current.workflowName + '/' + vance.workflow.current.state"
                        + " + '#' + vance.workflow.current.taskId")
                .withWorkflowRun(run());

        assertThat(executor.run(req).value()).isEqualTo("release/build#task-3");
    }

    @Test
    void current_exposesParamsAndVars() {
        ScriptRequest req = request(
                "vance.workflow.current.params.version + '@' + vance.workflow.current.vars.sha")
                .withWorkflowRun(run());

        assertThat(executor.run(req).value()).isEqualTo("1.0.0@abc");
    }

    @Test
    void current_cannotBeWrittenFromTheGuest() {
        // The guest may not fake a variable write; the journal is the
        // only writer and the return value the only channel back.
        ScriptRequest req = request(
                "try { vance.workflow.current.vars.sha = 'forged'; 'written'; }"
                        + " catch (e) { 'refused'; }")
                .withWorkflowRun(run());

        assertThat(executor.run(req).value()).isEqualTo("refused");
    }

    @Test
    void status_throws_intoTheGuest_whenMagratheaIsDisabled() {
        // No projector wired (the executor field is null in this test
        // build) — the script sees a named error, not a null answer.
        ScriptRequest req = request("vance.workflow.status('run-7')");

        assertThatThrownBy(() -> executor.run(req))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("vance.services.magrathea");
    }

    @Test
    void start_isReachable_andGoesThroughTheToolDispatcher() {
        // No workflow_start tool in this dispatcher, so the call must
        // fail as a tool error — proving the wrapper dispatches rather
        // than reaching past the tool layer into the service.
        ScriptHarness harness = ScriptHarness.builder()
                .script("vance.workflow.start({ name: 'release' }).workflowRunId")
                .mockTool("workflow_start", params -> Map.of(
                        "workflowRunId", "run-42",
                        "workflowName", params.get("name")))
                .build();

        ScriptResult result = harness.run();

        assertThat(result.value()).isEqualTo("run-42");
        assertThat(harness.lastCall("workflow_start").params())
                .containsEntry("name", "release");
    }
}
