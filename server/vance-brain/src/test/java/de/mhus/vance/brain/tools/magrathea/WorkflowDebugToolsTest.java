package de.mhus.vance.brain.tools.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunSimulationDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunStepDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.runs.RunSourceRegistry;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The run-side {@code workflow_*} tools over a mocked run registry. */
class WorkflowDebugToolsTest {

    private static final String RUN = "ab12";
    private static final String COMPOSITE = "workflow:ab12";

    private final PermissionService permissions = mock(PermissionService.class);
    private final SecurityContextFactory contexts = mock(SecurityContextFactory.class);
    private final RunSourceRegistry registry = mock(RunSourceRegistry.class);
    private final SecurityContext subject = SecurityContext.SYSTEM;
    private final WorkflowToolSupport support = new WorkflowToolSupport(permissions, contexts, registry);

    private final ToolInvocationContext ctx = new ToolInvocationContext("acme", "proj", null, null, "alice");

    @BeforeEach
    void subject() {
        when(contexts.forToolSubject("acme", "alice")).thenReturn(subject);
    }

    private static RunDetailDto run(
            RunStatus status, String step, Set<RunAction> actions, boolean debug, RunSimulationDto simulation) {
        return RunDetailDto.builder()
                .summary(RunSummaryDto.builder()
                        .runId(COMPOSITE)
                        .source("workflow")
                        .name("release")
                        .status(status)
                        .step(step)
                        .projectId("proj")
                        .build())
                .allowedActions(actions)
                .debug(debug)
                .simulation(simulation)
                .build();
    }

    private void registryHolds(RunDetailDto... successive) {
        var stub = when(registry.get(eq(subject), eq("acme"), eq("proj"), eq(COMPOSITE)));
        var chain = stub.thenReturn(Optional.of(successive[0]));
        for (int i = 1; i < successive.length; i++) chain = chain.thenReturn(Optional.of(successive[i]));
    }

    // ─── workflow_run_get ──────────────────────────────────────────────

    @Test
    void run_get_names_what_the_run_can_do_next() {
        registryHolds(run(
                RunStatus.PAUSED,
                "build",
                Set.of(RunAction.STEP, RunAction.RESUME, RunAction.STOP),
                true,
                RunSimulationDto.builder()
                        .state("build")
                        .kind("shell_task")
                        .outcomes(List.of("ok", "error"))
                        .build()));

        Map<String, Object> view = new WorkflowRunGetTool(support).invoke(Map.of("runId", RUN), ctx);

        assertThat(view)
                .containsEntry("runId", RUN)
                .containsEntry("status", "PAUSED")
                .containsEntry("state", "build");
        assertThat(view.get("allowedActions")).isEqualTo(List.of("resume", "step", "stop"));
        assertThat(view).containsKey("simulation");
        verify(permissions).enforce(subject, new Resource.Project("acme", "proj"), Action.READ);
    }

    @Test
    void run_get_accepts_the_composite_id_as_the_obvious_slip() {
        registryHolds(run(RunStatus.DONE, "end", Set.of(), false, null));

        Map<String, Object> view = new WorkflowRunGetTool(support).invoke(Map.of("runId", COMPOSITE), ctx);

        assertThat(view).containsEntry("runId", RUN);
    }

    @Test
    void run_get_cuts_long_step_output_unless_that_step_is_asked_for() {
        String long30k = "x".repeat(3_000);
        RunDetailDto detail = run(RunStatus.DONE, "end", Set.of(), false, null);
        detail.getSteps()
                .add(RunStepDto.builder()
                        .name("fetch")
                        .kind("tool_task")
                        .outcome("ok")
                        .output(long30k)
                        .build());
        registryHolds(detail);
        var tool = new WorkflowRunGetTool(support);

        List<?> cut = (List<?>) tool.invoke(Map.of("runId", RUN), ctx).get("steps");
        List<?> whole = (List<?>)
                tool.invoke(Map.of("runId", RUN, "step", "fetch"), ctx).get("steps");

        Map<?, ?> cutStep = (Map<?, ?>) cut.get(0);
        assertThat((String) cutStep.get("output")).hasSize(WorkflowToolSupport.STEP_OUTPUT_CHARS);
        assertThat(cutStep.get("outputCut")).isEqualTo(true);
        Map<?, ?> wholeStep = (Map<?, ?>) whole.get(0);
        assertThat((String) wholeStep.get("output")).hasSize(3_000);
        assertThat(wholeStep.containsKey("outputCut")).isFalse();
    }

    @Test
    void an_unknown_run_is_a_tool_error() {
        when(registry.get(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new WorkflowRunGetTool(support).invoke(Map.of("runId", "nope"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no workflow run 'nope'");
    }

    @Test
    void without_a_project_nothing_is_looked_up() {
        assertThatThrownBy(() -> new WorkflowRunGetTool(support)
                        .invoke(Map.of("runId", RUN), new ToolInvocationContext("acme", null, null, null, "alice")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("project context");
        verify(registry, never()).get(any(), any(), any(), any());
    }

    // ─── workflow_runs ─────────────────────────────────────────────────

    @Test
    void runs_filters_by_workflow_and_status_and_speaks_native_ids() {
        when(registry.list(eq(subject), eq("acme"), eq("proj"), any(Integer.class), eq("workflow")))
                .thenReturn(List.of(
                        summary("workflow:r1", "release", RunStatus.PAUSED),
                        summary("workflow:r2", "release", RunStatus.DONE),
                        summary("workflow:r3", "other", RunStatus.PAUSED)));

        Map<String, Object> out =
                new WorkflowRunsTool(support).invoke(Map.of("workflow", "release", "status", "paused"), ctx);

        assertThat(out).containsEntry("count", 1);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) out.get("runs")).get(0);
        assertThat(row.get("runId")).isEqualTo("r1");
    }

    @Test
    void runs_rejects_an_unknown_status() {
        assertThatThrownBy(() -> new WorkflowRunsTool(support).invoke(Map.of("status", "sleeping"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown status");
    }

    private static RunSummaryDto summary(String id, String name, RunStatus status) {
        return RunSummaryDto.builder()
                .runId(id)
                .source("workflow")
                .name(name)
                .status(status)
                .projectId("proj")
                .build();
    }

    // ─── workflow_control ──────────────────────────────────────────────

    @Test
    void control_performs_an_offered_action_and_needs_write() {
        registryHolds(
                run(RunStatus.PAUSED, "build", Set.of(RunAction.STEP), true, null),
                run(RunStatus.PAUSED, "deploy", Set.of(RunAction.STEP), true, null));

        Map<String, Object> out =
                new WorkflowControlTool(support).invoke(Map.of("runId", RUN, "action", "step", "waitSeconds", 2), ctx);

        verify(registry).perform(eq(subject), eq("acme"), eq("proj"), eq(COMPOSITE), eq(RunAction.STEP), any());
        verify(permissions).enforce(subject, new Resource.Project("acme", "proj"), Action.WRITE);
        assertThat(out).containsEntry("applied", true).containsEntry("state", "deploy");
    }

    @Test
    void control_says_so_when_the_action_is_not_offered() {
        registryHolds(run(RunStatus.DONE, "end", Set.of(), false, null));

        Map<String, Object> out = new WorkflowControlTool(support).invoke(Map.of("runId", RUN, "action", "stop"), ctx);

        verify(registry, never()).perform(any(), any(), any(), any(), any(), any());
        assertThat(out).containsEntry("applied", false);
        assertThat((String) out.get("note")).contains("not offered");
    }

    @Test
    void control_rejects_an_unknown_action() {
        assertThatThrownBy(
                        () -> new WorkflowControlTool(support).invoke(Map.of("runId", RUN, "action", "explode"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown action");
    }

    // ─── workflow_breakpoints ──────────────────────────────────────────

    @Test
    void breakpoints_replace_the_set_of_a_debug_run() {
        registryHolds(run(RunStatus.PAUSED, "build", Set.of(), true, null));

        new WorkflowBreakpointsTool(support).invoke(Map.of("runId", RUN, "states", List.of("deploy", " ")), ctx);

        verify(registry).setBreakpoints(subject, "acme", "proj", COMPOSITE, List.of("deploy"));
    }

    @Test
    void breakpoints_on_an_ordinary_run_say_how_to_get_a_debug_run() {
        registryHolds(run(RunStatus.RUNNING, "build", Set.of(), false, null));

        assertThatThrownBy(() ->
                        new WorkflowBreakpointsTool(support).invoke(Map.of("runId", RUN, "states", List.of()), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("workflow_restart");
        verify(registry, never()).setBreakpoints(any(), any(), any(), any(), any());
    }

    @Test
    void breakpoints_need_the_complete_list() {
        assertThatThrownBy(() -> new WorkflowBreakpointsTool(support).invoke(Map.of("runId", RUN), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'states' is required");
    }

    // ─── workflow_simulate ─────────────────────────────────────────────

    @Test
    void simulate_refuses_a_run_that_offers_nothing() {
        registryHolds(run(RunStatus.RUNNING, "build", Set.of(), true, null));

        assertThatThrownBy(() -> new WorkflowSimulateTool(support).invoke(Map.of("runId", RUN, "outcome", "ok"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("offers no simulation");
        verify(registry, never()).simulate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void simulate_hands_the_result_over_and_names_the_valid_outcomes_on_a_bad_one() {
        RunSimulationDto offer = RunSimulationDto.builder()
                .state("build")
                .kind("shell_task")
                .outcomes(List.of("ok", "error"))
                .build();
        registryHolds(
                run(RunStatus.PAUSED, "build", Set.of(), true, offer),
                run(RunStatus.PAUSED, "deploy", Set.of(), true, null));

        Map<String, Object> out =
                new WorkflowSimulateTool(support).invoke(Map.of("runId", RUN, "outcome", "ok", "output", "fine"), ctx);

        verify(registry).simulate(subject, "acme", "proj", COMPOSITE, "ok", "fine", null);
        assertThat(out).containsEntry("simulatedState", "build").containsEntry("state", "deploy");

        doThrow(new IllegalArgumentException("no way on for 'maybe'"))
                .when(registry)
                .simulate(any(), any(), any(), any(), eq("maybe"), any(), any());
        registryHolds(run(RunStatus.PAUSED, "build", Set.of(), true, offer));
        assertThatThrownBy(
                        () -> new WorkflowSimulateTool(support).invoke(Map.of("runId", RUN, "outcome", "maybe"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("[ok, error]");
    }

    // ─── workflow_restart ──────────────────────────────────────────────

    @Test
    void restart_returns_the_new_native_run_id_and_passes_the_debug_setup() {
        when(registry.restart(any(), any(), any(), any(), any(), any(), eq(true), any()))
                .thenReturn("workflow:cd34");

        Map<String, Object> out = new WorkflowRestartTool(support)
                .invoke(Map.of("runId", RUN, "fromState", "deploy", "debug", true), ctx);

        verify(registry).restart(subject, "acme", "proj", COMPOSITE, "deploy", "alice", true, null);
        assertThat(out)
                .containsEntry("workflowRunId", "cd34")
                .containsEntry("restartOf", RUN)
                .containsEntry("debug", true);
    }

    @Test
    void restart_of_a_run_that_still_goes_points_at_stop() {
        when(registry.restart(any(), any(), any(), any(), any(), any(), eq(false), any()))
                .thenThrow(new IllegalStateException("run still goes"));

        assertThatThrownBy(() -> new WorkflowRestartTool(support).invoke(Map.of("runId", RUN), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("workflow_control");
    }

    @Test
    void settled_waits_for_the_run_to_move_off_the_state_it_was_released_from() {
        RunDetailDto halted = run(RunStatus.PAUSED, "build", Set.of(), true, null);
        RunDetailDto stillThere = run(RunStatus.PAUSED, "build", Set.of(), true, null);
        RunDetailDto moved = run(RunStatus.PAUSED, "deploy", Set.of(), true, null);

        assertThat(WorkflowToolSupport.settled(stillThere, halted)).isFalse();
        assertThat(WorkflowToolSupport.settled(moved, halted)).isTrue();
        assertThat(WorkflowToolSupport.settled(run(RunStatus.RUNNING, "x", Set.of(), true, null), null))
                .isFalse();
        assertThat(WorkflowToolSupport.settled(stillThere, null)).isTrue();
    }
}
