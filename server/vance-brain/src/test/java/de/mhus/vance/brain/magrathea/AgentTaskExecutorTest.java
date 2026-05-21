package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentTaskExecutorTest {

    private final RecipeResolver recipeResolver = mock(RecipeResolver.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ThinkEngineService thinkEngineService = mock(ThinkEngineService.class);
    private final MagratheaSessionResolver sessionResolver = mock(MagratheaSessionResolver.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final AgentTaskExecutor executor = new AgentTaskExecutor(
            recipeResolver, thinkProcessService, thinkEngineService,
            sessionResolver, taskService);

    @Test
    void happy_path_spawns_process_and_returns_async() {
        stubResolver("jeltz");
        ThinkEngine engine = mockEngine("jeltz", "1");
        when(thinkEngineService.resolve("jeltz")).thenReturn(Optional.of(engine));
        SessionDocument session = new SessionDocument();
        session.setSessionId("sess-1");
        when(sessionResolver.resolve(any(), any(), any(), any())).thenReturn(session);
        ThinkProcessDocument spawned = new ThinkProcessDocument();
        spawned.setId("proc-1");
        when(thinkProcessService.create(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(spawned);

        Optional<TaskOutcome> outcome = executor.execute(ctx(agentState("jeltz",
                Map.of("prompt", "hi", "schema", Map.of()))));

        assertThat(outcome).isEmpty(); // async
        verify(taskService).linkSubProcess("task-1", "proc-1");
        verify(thinkEngineService).start(spawned);
    }

    @Test
    void missing_recipe_field_fails_synchronously() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(agentState(null, Map.of())));

        assertThat(outcome).isPresent();
        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("'recipe:'");
        verify(taskService, never()).linkSubProcess(any(), any());
    }

    @Test
    void unknown_recipe_returns_failure() {
        when(recipeResolver.applyDefaulting(any(), any(), eq("ghost"), any(), any(), any()))
                .thenReturn(Optional.empty());

        Optional<TaskOutcome> outcome = executor.execute(ctx(agentState("ghost", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("not found");
    }

    @Test
    void recipe_resolver_exception_returns_failure() {
        when(recipeResolver.applyDefaulting(any(), any(), eq("boom"), any(), any(), any()))
                .thenThrow(new RuntimeException("YAML invalid"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(agentState("boom", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("YAML invalid");
    }

    @Test
    void unknown_engine_returns_failure() {
        stubResolver("jeltz");
        when(thinkEngineService.resolve("jeltz")).thenReturn(Optional.empty());

        Optional<TaskOutcome> outcome = executor.execute(ctx(agentState("jeltz", Map.of())));

        assertThat(outcome.get().errorMessage()).contains("unknown engine");
    }

    @Test
    void start_failure_after_create_returns_failure() {
        stubResolver("jeltz");
        ThinkEngine engine = mockEngine("jeltz", "1");
        when(thinkEngineService.resolve("jeltz")).thenReturn(Optional.of(engine));
        SessionDocument session = new SessionDocument();
        session.setSessionId("sess-1");
        when(sessionResolver.resolve(any(), any(), any(), any())).thenReturn(session);
        ThinkProcessDocument spawned = new ThinkProcessDocument();
        spawned.setId("proc-1");
        when(thinkProcessService.create(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(spawned);
        doThrow(new RuntimeException("engine start failed")).when(thinkEngineService).start(spawned);

        Optional<TaskOutcome> outcome = executor.execute(ctx(agentState("jeltz", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("Engine start failed");
    }

    // ─────── helpers ───────

    private void stubResolver(String recipeName) {
        AppliedRecipe applied = new AppliedRecipe(
                recipeName, recipeName,
                Map.of("model", "default:fast"),
                /*promptOverride*/ null,
                /*promptOverrideAppend*/ null,
                PromptMode.APPEND,
                /*dataRelayCorrection*/ null,
                /*effectiveAllowedTools*/ null,
                /*connectionProfile*/ null,
                /*defaultActiveSkills*/ List.of(),
                /*allowedSkills*/ null,
                RecipeSource.PROJECT,
                /*overriddenParamKeys*/ List.of(),
                /*sessionLifecycleConfig*/ null);
        when(recipeResolver.applyDefaulting(any(), any(), eq(recipeName), any(), any(), any()))
                .thenReturn(Optional.of(applied));
    }

    private static ThinkEngine mockEngine(String name, String version) {
        ThinkEngine e = mock(ThinkEngine.class);
        when(e.name()).thenReturn(name);
        when(e.version()).thenReturn(version);
        return e;
    }

    private static MagratheaStateSpec agentState(String recipe, Map<String, Object> params) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (recipe != null) spec.put("recipe", recipe);
        spec.put("params", params);
        return new MagratheaStateSpec(
                "plan",
                MagratheaTaskType.AGENT_TASK,
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
