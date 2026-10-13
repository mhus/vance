package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.inherit.ParentContextSpawnHelper;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeLoader;
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

class SpawnActionExecutorTest {

    private RecipeResolver recipeResolver;
    private RecipeLoader recipeLoader;
    private ThinkEngineService engineService;
    private ThinkProcessService processService;
    private EngineMessageRouter messageRouter;
    private ParentContextSpawnHelper parentContextSpawnHelper;
    private de.mhus.vance.brain.skill.SkillSteerProcessor skillSteerProcessor;
    private de.mhus.vance.brain.skill.SkillTurnSupport skillTurnSupport;
    private SpawnActionExecutor exec;

    private final TriggerContext ctxWithSession =
            TriggerContext.sessioned("t1", "p1", "alice", "corr-1", "scheduler:morning", "sess-1", null);

    @BeforeEach
    void setUp() {
        recipeResolver = mock(RecipeResolver.class);
        recipeLoader = mock(RecipeLoader.class);
        engineService = mock(ThinkEngineService.class);
        processService = mock(ThinkProcessService.class);
        messageRouter = mock(EngineMessageRouter.class);
        parentContextSpawnHelper = mock(ParentContextSpawnHelper.class);
        skillSteerProcessor = mock(de.mhus.vance.brain.skill.SkillSteerProcessor.class);
        skillTurnSupport = mock(de.mhus.vance.brain.skill.SkillTurnSupport.class);
        when(parentContextSpawnHelper.wrap(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        de.mhus.vance.brain.tools.worktarget.WorkTargetService workTargetService =
                mock(de.mhus.vance.brain.tools.worktarget.WorkTargetService.class);
        // Pass-through: no inheritance unless the test wires it up.
        when(workTargetService.resolveSpawnParams(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        exec = new SpawnActionExecutor(
                recipeResolver,
                recipeLoader,
                providerOf(engineService),
                processService,
                providerOf(messageRouter),
                parentContextSpawnHelper,
                workTargetService,
                providerOf(skillSteerProcessor),
                providerOf(skillTurnSupport));
    }

    // ──────────────────── Happy path ────────────────────

    @Test
    void happy_path_returns_scheduled_with_process_id_and_output() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-42");

        ActionResult r = exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", null, Map.of("k", "v"), null),
                ctxWithSession,
                TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SCHEDULED);
        assertThat(r.spawnedId()).isEqualTo("proc-42");
        assertThat(r.errorMessage()).isNull();
        assertThat(r.output()).isNotNull();
        assertThat(r.output()).containsKeys("processId", "name", "engine", "engineVersion");
        assertThat(r.output().get("processId")).isEqualTo("proc-42");
        assertThat(r.output().get("engine")).isEqualTo("arthur");
        verify(messageRouter, never()).dispatch(any(), any(), any());
    }

    @Test
    void initialMessage_dispatched_via_router() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-7");
        when(messageRouter.dispatch(isNull(), eq("proc-7"), any())).thenReturn(true);

        exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", "Hello there", null, null), ctxWithSession, TriggerKind.SCHEDULER));

        ArgumentCaptor<PendingMessageDocument> msgCaptor = ArgumentCaptor.forClass(PendingMessageDocument.class);
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
                TriggerAction.Recipe.of("analyze", "   ", null, null), ctxWithSession, TriggerKind.SCHEDULER));

        verify(messageRouter, never()).dispatch(any(), any(), any());
    }

    // ──────────────────── Skills handed to the worker (skills.md §7f) ────────────────────

    private static TriggerAction.Recipe withSkills(List<String> skills) {
        return new TriggerAction.Recipe("analyze", "w1", null, null, null, null, "do it", null, null, skills);
    }

    private static de.mhus.vance.brain.skill.ResolvedSkill skill(
            String name, de.mhus.vance.brain.skill.SkillDelegation delegation) {
        return new de.mhus.vance.brain.skill.ResolvedSkill(
                name,
                name,
                "desc",
                "1.0.0",
                List.of(),
                "body",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                true,
                de.mhus.vance.api.skills.SkillScope.RESOURCE,
                List.of(),
                List.of(),
                de.mhus.vance.brain.skill.SkillLifecycle.TURN,
                false,
                List.of(),
                null,
                de.mhus.vance.brain.skill.SkillRun.INLINE,
                null,
                delegation);
    }

    @Test
    void explicitSkills_areActivatedStickyBeforeTheEngineStarts() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-s");

        ActionResult r = exec.execute(
                new ActionInvocation<>(withSkills(List.of("report-writing")), ctxWithSession, TriggerKind.TOOL));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SCHEDULED);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(skillSteerProcessor, engineService);
        order.verify(skillSteerProcessor)
                .activate(
                        any(),
                        eq("report-writing"),
                        eq(false),
                        eq(true),
                        eq(de.mhus.vance.brain.skill.ActivationRoute.IMPLICIT),
                        isNull(),
                        isNull());
        order.verify(engineService).start(any());
    }

    @Test
    void explicitSkill_thatCannotBeActivated_failsTheSpawnAndClosesTheProcess() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-x");
        when(skillSteerProcessor.activate(any(), eq("ghost"), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenThrow(new de.mhus.vance.brain.skill.UnknownSkillException("ghost"));

        ActionResult r =
                exec.execute(new ActionInvocation<>(withSkills(List.of("ghost")), ctxWithSession, TriggerKind.TOOL));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.BUSINESS_ERROR);
        assertThat(r.errorMessage()).contains("skill 'ghost'");
        verify(processService).closeProcess(eq("proc-x"), any());
        verify(engineService, never()).start(any());
    }

    @Test
    void parentCarrySkills_goAlong_stayAndRecipeBoundDoNot() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-c");
        ThinkProcessDocument parent = ThinkProcessDocument.builder()
                .id("parent-1")
                .activeSkills(new java.util.ArrayList<>(List.of(
                        de.mhus.vance.shared.skill.ActiveSkillRefEmbedded.builder()
                                .name("report-writing")
                                .build(),
                        de.mhus.vance.shared.skill.ActiveSkillRefEmbedded.builder()
                                .name("decision-frame")
                                .build(),
                        de.mhus.vance.shared.skill.ActiveSkillRefEmbedded.builder()
                                .name("house-style")
                                .fromRecipe(true)
                                .build())))
                .build();
        when(processService.findById("parent-1")).thenReturn(java.util.Optional.of(parent));
        when(skillTurnSupport.resolveActive(parent))
                .thenReturn(List.of(
                        skill("report-writing", de.mhus.vance.brain.skill.SkillDelegation.CARRY),
                        skill("decision-frame", de.mhus.vance.brain.skill.SkillDelegation.STAY),
                        skill("house-style", de.mhus.vance.brain.skill.SkillDelegation.CARRY)));
        TriggerContext fromParent =
                TriggerContext.sessioned("t1", "p1", "alice", "corr-1", "tool:process_spawn", "sess-1", "parent-1");

        exec.execute(new ActionInvocation<>(withSkills(null), fromParent, TriggerKind.TOOL));

        verify(skillSteerProcessor)
                .activate(any(), eq("report-writing"), eq(false), eq(true), any(), isNull(), isNull());
        verify(skillSteerProcessor, never())
                .activate(any(), eq("decision-frame"), anyBoolean(), anyBoolean(), any(), any(), any());
        verify(skillSteerProcessor, never())
                .activate(any(), eq("house-style"), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    // ──────────────────── Soft-success: already-exists ────────────────────

    @Test
    void already_exists_returns_soft_success_with_hint() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        when(processService.create(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any()))
                .thenThrow(new ThinkProcessService.ThinkProcessAlreadyExistsException(
                        "Think-process 'dup' already exists"));
        ThinkProcessDocument existing = ThinkProcessDocument.builder()
                .id("existing-id")
                .name("dup")
                .thinkEngine("arthur")
                .recipeName("analyze")
                .status(ThinkProcessStatus.IDLE)
                .build();
        when(processService.findByName("t1", "sess-1", "dup")).thenReturn(Optional.of(existing));

        ActionResult r = exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", "dup", null, null, null, null, null, null, null),
                ctxWithSession,
                TriggerKind.TOOL));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.spawnedId()).isNull();
        assertThat(r.output()).isNotNull();
        assertThat(r.output().get("status")).isEqualTo("already_exists");
        assertThat(r.output().get("existingProcessId")).isEqualTo("existing-id");
        assertThat(r.output().get("existingEngine")).isEqualTo("arthur");
        assertThat(r.output()).containsKey("hint");
    }

    // ──────────────────── Strict unknown-recipe ────────────────────

    @Test
    void unknown_recipe_returns_failure_with_suggestions() {
        when(recipeResolver.applyDefaulting(any(), any(), eq("ghost"), any(), any()))
                .thenThrow(new RecipeResolver.UnknownRecipeException("ghost"));
        when(recipeLoader.listAll(any(), any()))
                .thenReturn(List.of(
                        stubResolvedRecipe("analyze"), stubResolvedRecipe("research"), stubResolvedRecipe("ghosts")));

        ActionResult r = exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("ghost", null, null, null), ctxWithSession, TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("ghost");
        assertThat(r.output()).isNotNull();
        assertThat(r.output().get("requested")).isEqualTo("ghost");
        @SuppressWarnings("unchecked")
        List<Object> suggestions = (List<Object>) r.output().get("suggestions");
        @SuppressWarnings("unchecked")
        List<Object> available = (List<Object>) r.output().get("available");
        assertThat(suggestions).contains("ghosts");
        assertThat(available).contains("analyze", "research", "ghosts");
    }

    // ──────────────────── Failure paths ────────────────────

    @Test
    void standalone_context_returns_technical_error() {
        TriggerContext noSession = TriggerContext.standalone("t1", "p1", "alice", null, null, null);

        ActionResult r = exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", null, null, null), noSession, TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("Sessioned");
    }

    @Test
    void recipe_resolver_throwing_maps_to_technical_error() {
        when(recipeResolver.applyDefaulting(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("cascade broken"));

        ActionResult r = exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", null, null, null), ctxWithSession, TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("resolution");
    }

    @Test
    void unknown_engine_referenced_by_recipe_returns_technical_error() {
        stubRecipe("analyze", "ghost-engine");
        when(engineService.resolve("ghost-engine")).thenReturn(Optional.empty());

        ActionResult r = exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", null, null, null), ctxWithSession, TriggerKind.SCHEDULER));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("ghost-engine");
    }

    @Test
    void thinkProcessService_throwing_maps_to_technical_error() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        when(processService.create(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("mongo timeout"));

        ActionResult r = exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", null, null, null), ctxWithSession, TriggerKind.SCHEDULER));

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
        TriggerContext ctxWithParent =
                TriggerContext.sessioned("t1", "p1", "alice", null, "tool:x", "sess-1", "parent-proc");

        exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", null, null, null), ctxWithParent, TriggerKind.TOOL));

        verify(processService)
                .create(
                        eq("t1"),
                        eq("p1"),
                        eq("sess-1"),
                        any(),
                        eq("arthur"),
                        eq("1.0"),
                        any(),
                        any(),
                        eq("parent-proc"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void caller_processName_overrides_auto_generated() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-named");

        exec.execute(new ActionInvocation<>(
                new TriggerAction.Recipe("analyze", "explicit-name", null, null, null, null, null, null, null),
                ctxWithSession,
                TriggerKind.TOOL));

        verify(processService)
                .create(
                        any(),
                        any(),
                        any(),
                        eq("explicit-name"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void inheritContext_wrap_invoked_when_parent_present() {
        stubRecipe("analyze", "arthur");
        stubEngine("arthur", "1.0");
        stubCreateReturnsProcess("proc-wrap");
        when(messageRouter.dispatch(any(), any(), any())).thenReturn(true);
        when(parentContextSpawnHelper.wrap(any(), eq("parent-proc"), eq("the goal")))
                .thenReturn("## Parent context …\n\n## Your task\n\nthe goal");
        TriggerContext ctxWithParent =
                TriggerContext.sessioned("t1", "p1", "alice", null, "tool:x", "sess-1", "parent-proc");

        exec.execute(new ActionInvocation<>(
                TriggerAction.Recipe.of("analyze", "the goal", null, null), ctxWithParent, TriggerKind.TOOL));

        verify(parentContextSpawnHelper).wrap(any(), eq("parent-proc"), eq("the goal"));
        ArgumentCaptor<PendingMessageDocument> capt = ArgumentCaptor.forClass(PendingMessageDocument.class);
        verify(messageRouter).dispatch(eq("parent-proc"), eq("proc-wrap"), capt.capture());
        assertThat(capt.getValue().getContent()).contains("Parent context");
    }

    // ──────────────────── Helpers ────────────────────

    private void stubRecipe(String recipeName, String engineName) {
        AppliedRecipe applied = new AppliedRecipe(
                recipeName,
                engineName,
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
        when(recipeResolver.applyDefaulting(any(), any(), eq(recipeName), any(), any()))
                .thenReturn(applied);
    }

    private void stubEngine(String name, String version) {
        ThinkEngine engine = mock(ThinkEngine.class);
        when(engine.name()).thenReturn(name);
        when(engine.version()).thenReturn(version);
        when(engineService.resolve(name)).thenReturn(Optional.of(engine));
    }

    private void stubCreateReturnsProcess(String processId) {
        stubCreateReturnsProcess(processId, "arthur", "analyze");
    }

    private void stubCreateReturnsProcess(
            String processId, String engine, @org.jspecify.annotations.Nullable String recipe) {
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .id(processId)
                .tenantId("t1")
                .projectId("p1")
                .sessionId("sess-1")
                .name("run_x")
                .thinkEngine(engine)
                .thinkEngineVersion("1.0")
                .recipeName(recipe)
                .status(ThinkProcessStatus.IDLE)
                .build();
        // 12-arg overload (engine-direct path)
        when(processService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);
        // 19-arg overload (recipe-driven path)
        when(processService.create(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);
        when(processService.findById(processId)).thenReturn(Optional.of(doc));
    }

    private static de.mhus.vance.brain.recipe.ResolvedRecipe stubResolvedRecipe(String name) {
        return new de.mhus.vance.brain.recipe.ResolvedRecipe(
                name, /*description*/
                "", /*engine*/
                "arthur",
                /*params*/ Map.of(),
                /*promptPrefix*/ null,
                PromptMode.APPEND,
                /*dataRelayCorrection*/ null,
                /*allowedToolsAdd*/ List.of(),
                /*allowedToolsRemove*/ List.of(),
                /*allowedToolsDefer*/ List.of(),
                /*modes*/ Map.of(),
                /*profiles*/ Map.of(),
                /*defaultActiveSkills*/ List.of(),
                /*allowedSkills*/ null,
                /*triggerKeywords*/ List.of(),
                /*locked*/ false,
                /*internal*/ false,
                /*listed*/ false,
                /*title*/ null,
                /*tags*/ List.of(),
                List.of(),
                RecipeSource.PROJECT);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        when(provider.getObject()).thenReturn(bean);
        return provider;
    }
}
