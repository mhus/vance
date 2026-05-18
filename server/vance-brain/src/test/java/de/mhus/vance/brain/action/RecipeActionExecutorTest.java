package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class RecipeActionExecutorTest {

    private RecipeResolver recipeResolver;
    private ThinkEngineService engineService;
    private ThinkProcessService processService;
    private EngineMessageRouter messageRouter;
    private RecipeActionExecutor exec;

    private final TriggerContext ctxWithSession = new TriggerContext(
            "t1", "p1", "alice", "corr-1", "scheduler:morning", "sess-1", null);

    @BeforeEach
    void setUp() {
        recipeResolver = mock(RecipeResolver.class);
        engineService = mock(ThinkEngineService.class);
        processService = mock(ThinkProcessService.class);
        messageRouter = mock(EngineMessageRouter.class);
        exec = new RecipeActionExecutor(
                recipeResolver,
                providerOf(engineService),
                processService,
                providerOf(messageRouter));
    }

    // ──────────────────── Happy path ────────────────────

    @Test
    void happy_path_returns_scheduled_with_process_id() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-42");

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", null, Map.of("k", "v"), null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SCHEDULED);
        assertThat(r.spawnedId()).isEqualTo("proc-42");
        assertThat(r.errorMessage()).isNull();
        verify(messageRouter, never()).dispatch(any(), any(), any());
    }

    @Test
    void initialMessage_dispatched_via_router() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-7");
        when(messageRouter.dispatch(isNull(), eq("proc-7"), any())).thenReturn(true);

        exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", "Hello there", null, null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        ArgumentCaptor<PendingMessageDocument> msgCaptor =
                ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(messageRouter).dispatch(isNull(), eq("proc-7"), msgCaptor.capture());
        assertThat(msgCaptor.getValue().getContent()).isEqualTo("Hello there");
        assertThat(msgCaptor.getValue().getFromUser()).isEqualTo("scheduler:morning");
    }

    @Test
    void blank_initialMessage_does_not_dispatch() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-1");

        exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", "   ", null, null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        verify(messageRouter, never()).dispatch(any(), any(), any());
    }

    // ──────────────────── Failure paths ────────────────────

    @Test
    void missing_parentSessionId_returns_technical_error() {
        TriggerContext noSession = new TriggerContext(
                "t1", "p1", "alice", null, null, /*parentSessionId*/ null, null);

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", null, null, null),
                noSession,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("parentSessionId");
    }

    @Test
    void unknown_recipe_returns_technical_error() {
        when(recipeResolver.applyDefaulting(any(), any(), eq("ghost"), any(), any(), any()))
                .thenReturn(Optional.empty());

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("ghost", null, null, null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("unknown recipe 'ghost'");
    }

    @Test
    void recipe_resolver_throwing_maps_to_technical_error() {
        when(recipeResolver.applyDefaulting(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("cascade broken"));

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", null, null, null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("recipe_resolution");
    }

    @Test
    void unknown_engine_returns_technical_error() {
        stubRecipe("analyze", "ghost-engine");
        when(engineService.resolve("ghost-engine")).thenReturn(Optional.empty());

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", null, null, null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("engine_resolution");
    }

    @Test
    void thinkProcessService_throwing_maps_to_technical_error() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        when(processService.create(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenThrow(new RuntimeException("mongo timeout"));

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", null, null, null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("process_create");
    }

    // ──────────────────── Misc ────────────────────

    @Test
    void actionType_returns_recipe_class() {
        assertThat(exec.actionType()).isEqualTo(TriggerAction.Recipe.class);
    }

    @Test
    void parentProcessId_from_context_passed_to_create() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-x");
        TriggerContext ctxWithParent = new TriggerContext(
                "t1", "p1", "alice", null, "tool:x", "sess-1", "parent-proc");

        exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", null, null, null),
                ctxWithParent,
                TriggerKind.TOOL));

        verify(processService).create(
                eq("t1"), eq("p1"), eq("sess-1"), any(), eq("arthur"), eq("1.0"),
                any(), any(), eq("parent-proc"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    // ──────────────────── Helpers ────────────────────

    private void stubRecipe(String recipeName, String engineName) {
        AppliedRecipe applied = new AppliedRecipe(
                recipeName, engineName,
                Map.of("model", "default:fast"),
                /*promptOverride*/ null,
                /*promptOverrideAppend*/ null,
                PromptMode.APPEND,
                /*dataRelayCorrection*/ null,
                /*effectiveAllowedTools*/ null,
                /*connectionProfile*/ "scheduler",
                /*defaultActiveSkills*/ List.of(),
                /*allowedSkills*/ null,
                RecipeSource.PROJECT,
                /*overriddenParamKeys*/ List.of(),
                /*sessionLifecycleConfig*/ null);
        when(recipeResolver.applyDefaulting(any(), any(), eq(recipeName), any(), any(), any()))
                .thenReturn(Optional.of(applied));
    }

    private void stubEngine(String name, String version) {
        ThinkEngine engine = mock(ThinkEngine.class);
        when(engine.name()).thenReturn(name);
        when(engine.version()).thenReturn(version);
        when(engineService.resolve(name)).thenReturn(Optional.of(engine));
    }

    private void stubCreateReturnsProcess(String processId) {
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .id(processId)
                .tenantId("t1")
                .projectId("p1")
                .sessionId("sess-1")
                .name("run_x")
                .build();
        when(processService.create(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(doc);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        when(provider.getObject()).thenReturn(bean);
        return provider;
    }
}
