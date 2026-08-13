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
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
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
    private final de.mhus.vance.brain.scheduling.LaneScheduler laneScheduler =
            mock(de.mhus.vance.brain.scheduling.LaneScheduler.class);
    private final EngineMessageRouter messageRouter = mock(EngineMessageRouter.class);
    private final MagratheaTimeoutScheduler timeoutScheduler = mock(MagratheaTimeoutScheduler.class);
    private final AgentTaskExecutor executor = new AgentTaskExecutor(
            recipeResolver, thinkProcessService, thinkEngineService,
            sessionResolver, taskService, laneScheduler,
            routerProvider(messageRouter), timeoutScheduler);

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<EngineMessageRouter>
            routerProvider(EngineMessageRouter router) {
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(router);
        return provider;
    }

    @org.junit.jupiter.api.BeforeEach
    @SuppressWarnings("unchecked")
    void wireLane() {
        // Run the submitted start() synchronously on the calling thread so the
        // executor's on-lane routing behaves like the direct call did in tests.
        when(laneScheduler.submit(any(String.class), any(java.util.concurrent.Callable.class)))
                .thenAnswer(inv -> {
                    java.util.concurrent.Callable<?> c = inv.getArgument(1);
                    try {
                        return java.util.concurrent.CompletableFuture.completedFuture(c.call());
                    } catch (Exception e) {
                        return java.util.concurrent.CompletableFuture.failedFuture(e);
                    }
                });
    }

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
    void spawn_deliversPromptAsInitialMessage_soAReactiveEngineActuallyRuns() {
        // The gap this closes: Ford & co. wait for a message. Without one the
        // worker idles forever and the run waits on it forever — a hang, not
        // a failure, so nothing ever routes through `catch:`.
        stubResolver("ford", Map.of("model", "default:fast", "prompt", "do the thing"));
        stubSpawn("ford");

        executor.execute(ctx(agentState("ford", Map.of("prompt", "do the thing"))));

        var msg = org.mockito.ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(messageRouter).dispatch(eq(null), eq("proc-1"), msg.capture());
        assertThat(msg.getValue().getContent()).isEqualTo("do the thing");
        assertThat(msg.getValue().getType()).isEqualTo(PendingMessageType.USER_CHAT_INPUT);
    }

    @Test
    void spawn_stripsDelegationTools_soTheStepStaysTheWholeStep() {
        // An agent that spawns its own workers builds a plan beside the
        // workflow: invisible in the diagram and past bounds.maxTaskSpawns.
        // It would also make IDLE ambiguous — "done" or "waiting for my
        // worker" — and the turn-end completion rule depends on it not being.
        stubResolver("ford", Map.of("model", "default:fast"));
        ThinkEngine engine = mockEngine("ford", "1");
        when(engine.allowedTools()).thenReturn(
                new java.util.LinkedHashSet<>(List.of("process_spawn", "doc_read", "web_search")));
        when(thinkEngineService.resolve("ford")).thenReturn(Optional.of(engine));
        SessionDocument session = new SessionDocument();
        session.setSessionId("sess-1");
        when(sessionResolver.resolve(any(), any(), any(), any())).thenReturn(session);
        ThinkProcessDocument spawned = new ThinkProcessDocument();
        spawned.setId("proc-1");
        var tools = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
        when(thinkProcessService.create(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), tools.capture()))
                .thenReturn(spawned);

        executor.execute(ctx(agentState("ford", Map.of())));

        assertThat(tools.getValue())
                .doesNotContain("process_spawn")
                .contains("doc_read", "web_search");
    }

    @Test
    void spawn_withoutAPrompt_sendsNothing() {
        stubResolver("ford", Map.of("model", "default:fast"));
        stubSpawn("ford");

        executor.execute(ctx(agentState("ford", Map.of())));

        verify(messageRouter, never()).dispatch(any(), any(), any());
    }

    @Test
    void spawn_survivesAnUnavailableRouter() {
        // Fail-soft: a missing router must not turn into a failed task — the
        // process is already spawned and linked at that point.
        var executorWithoutRouter = new AgentTaskExecutor(
                recipeResolver, thinkProcessService, thinkEngineService,
                sessionResolver, taskService, laneScheduler, routerProvider(null), timeoutScheduler);
        stubResolver("ford", Map.of("prompt", "hi"));
        stubSpawn("ford");

        assertThat(executorWithoutRouter.execute(
                ctx(agentState("ford", Map.of("prompt", "hi"))))).isEmpty();
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
        when(recipeResolver.applyDefaulting(any(), any(), eq("ghost"), any(), any()))
                .thenThrow(new de.mhus.vance.brain.recipe.RecipeResolver
                        .UnknownRecipeException("ghost"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(agentState("ghost", Map.of())));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("ghost");
    }

    @Test
    void recipe_resolver_exception_returns_failure() {
        when(recipeResolver.applyDefaulting(any(), any(), eq("boom"), any(), any()))
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
        // The unstarted process must not linger as an orphan, and it must
        // be unlinked so the completion listener won't match it later.
        verify(taskService).unlinkSubProcess("task-1");
        verify(thinkProcessService).closeProcess(
                "proc-1", de.mhus.vance.api.thinkprocess.CloseReason.ABANDONED);
    }

    // ─────── helpers ───────

    /** Engine + session + process-create wiring for a spawn that reaches start(). */
    private void stubSpawn(String recipeName) {
        ThinkEngine engine = mockEngine(recipeName, "1");
        when(thinkEngineService.resolve(recipeName)).thenReturn(Optional.of(engine));
        SessionDocument session = new SessionDocument();
        session.setSessionId("sess-1");
        when(sessionResolver.resolve(any(), any(), any(), any())).thenReturn(session);
        ThinkProcessDocument spawned = new ThinkProcessDocument();
        spawned.setId("proc-1");
        when(thinkProcessService.create(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(spawned);
    }

    private void stubResolver(String recipeName) {
        stubResolver(recipeName, Map.of("model", "default:fast"));
    }

    private void stubResolver(String recipeName, Map<String, Object> params) {
        AppliedRecipe applied = new AppliedRecipe(
                recipeName, recipeName,
                params,
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
        when(recipeResolver.applyDefaulting(any(), any(), eq(recipeName), any(), any()))
                .thenReturn(applied);
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
