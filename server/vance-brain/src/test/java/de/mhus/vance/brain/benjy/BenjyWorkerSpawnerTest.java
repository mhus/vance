package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BenjyWorkerSpawnerTest {

    private final RecipeResolver recipeResolver = mock(RecipeResolver.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ThinkEngineService thinkEngineService = mock(ThinkEngineService.class);
    private final LaneScheduler laneScheduler = mock(LaneScheduler.class);
    private final EngineMessageRouter messageRouter = mock(EngineMessageRouter.class);
    private final WorkTargetService workTargetService = mock(WorkTargetService.class);
    private final BenjyWorkerSpawner spawner = new BenjyWorkerSpawner(
            recipeResolver,
            thinkProcessService,
            provider(thinkEngineService),
            laneScheduler,
            provider(messageRouter),
            workTargetService);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireLane() {
        // Run the submitted start() + seed synchronously so the spawn behaves
        // like the direct call it is in a unit context.
        when(laneScheduler.submit(any(String.class), any(java.util.concurrent.Callable.class)))
                .thenAnswer(inv -> {
                    java.util.concurrent.Callable<?> c = inv.getArgument(1);
                    try {
                        return java.util.concurrent.CompletableFuture.completedFuture(c.call());
                    } catch (Exception e) {
                        return java.util.concurrent.CompletableFuture.failedFuture(e);
                    }
                });
        when(workTargetService.resolveSpawnParams(any(), any())).thenReturn(Map.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static ThinkEngine mockEngine(String name) {
        ThinkEngine e = mock(ThinkEngine.class);
        when(e.name()).thenReturn(name);
        when(e.version()).thenReturn("1");
        return e;
    }

    private void stubRecipe(String recipeName, List<String> defaultActiveSkills, List<String> allowedSkills) {
        AppliedRecipe applied = new AppliedRecipe(
                recipeName,
                "ford",
                Map.of(),
                /*promptOverride*/ null,
                /*promptOverrideAppend*/ null,
                PromptMode.APPEND,
                /*dataRelayCorrection*/ null,
                /*effectiveAllowedTools*/ null,
                /*connectionProfile*/ null,
                defaultActiveSkills,
                allowedSkills,
                RecipeSource.PROJECT,
                List.of(),
                /*sessionLifecycleConfig*/ null);
        when(recipeResolver.applyDefaulting(any(), any(), eq(recipeName), any(), any()))
                .thenReturn(applied);
        ThinkEngine engine = mockEngine("ford");
        when(thinkEngineService.resolve("ford")).thenReturn(Optional.of(engine));
    }

    /** A hub-side active skill ref, as the Benjy engine hands it over. */
    private static ActiveSkillRefEmbedded hubRef(String name, @org.jspecify.annotations.Nullable String args) {
        return ActiveSkillRefEmbedded.builder().name(name).args(args).build();
    }

    private static ActiveSkillRefEmbedded recipeRef(String name) {
        return ActiveSkillRefEmbedded.builder().name(name).fromRecipe(true).build();
    }

    @SuppressWarnings("unchecked")
    private List<ActiveSkillRefEmbedded> capturedReplacement(String workerId) {
        var list = ArgumentCaptor.forClass(List.class);
        verify(thinkProcessService).replaceActiveSkills(eq(workerId), list.capture());
        return list.getValue();
    }

    private static ThinkProcessDocument parent() {
        ThinkProcessDocument parent = new ThinkProcessDocument();
        parent.setId("benjy-1");
        parent.setTenantId("acme");
        parent.setProjectId("proj");
        parent.setSessionId("sess-1");
        return parent;
    }

    @Test
    @SuppressWarnings("unchecked")
    void spawn_passesRecipeSkillConfig_throughToTheDoer() {
        // Regression for the mid-overload gap: a doer recipe pinning
        // defaultActiveSkills/allowedSkills never saw them reach the process
        // (planning/skill-activation-levels.md §1.3.3).
        stubRecipe("benjy-do-coding", List.of("code-review"), List.of("code-review", "typescript-style"));
        ThinkProcessDocument worker = new ThinkProcessDocument();
        worker.setId("doer-1");
        var defaults = ArgumentCaptor.forClass(List.class);
        var whitelist = ArgumentCaptor.forClass(java.util.Set.class);
        when(thinkProcessService.create(
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
                        any(),
                        any(),
                        any(),
                        defaults.capture(),
                        whitelist.capture()))
                .thenReturn(worker);

        BenjyWorkerSpawner.DoerSpawn id = spawner.spawn(parent(), "benjy-do-coding", Map.of(), "item", List.of());

        assertThat(id.processId()).isEqualTo("doer-1");
        assertThat(id.droppedSkills()).isEmpty();
        assertThat(defaults.getValue()).containsExactly("code-review");
        assertThat(whitelist.getValue()).containsExactlyInAnyOrder("code-review", "typescript-style");
    }

    @Test
    void spawn_withoutRecipeSkills_passesEmptyDefaults_andNoWhitelist() {
        stubRecipe("benjy-do-coding", List.of(), null);
        ThinkProcessDocument worker = new ThinkProcessDocument();
        worker.setId("doer-2");
        var defaults = org.mockito.ArgumentCaptor.forClass(List.class);
        var whitelist = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
        when(thinkProcessService.create(
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
                        any(),
                        any(),
                        any(),
                        defaults.capture(),
                        whitelist.capture()))
                .thenReturn(worker);

        spawner.spawn(parent(), "benjy-do-coding", Map.of(), "item", List.of());

        assertThat(defaults.getValue()).isEmpty();
        assertThat(whitelist.getValue()).isNull();
        // Nothing inherited → no second write.
        verify(thinkProcessService, never()).replaceActiveSkills(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void spawn_inheritsHubSkills_onTopOfRecipeDefaults() {
        // §3.6.5: the hub's active skills ride along into the doer on top of
        // the recipe's own defaultActiveSkills — dedup, order kept.
        stubRecipe("benjy-do-coding", List.of("recipe-skill"), null);
        ThinkProcessDocument worker = new ThinkProcessDocument();
        worker.setId("doer-3");
        var defaults = org.mockito.ArgumentCaptor.forClass(List.class);
        when(thinkProcessService.create(
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
                        any(),
                        any(),
                        any(),
                        defaults.capture(),
                        any()))
                .thenReturn(worker);

        worker.setActiveSkills(List.of(recipeRef("recipe-skill")));

        spawner.spawn(
                parent(),
                "benjy-do-coding",
                Map.of(),
                "item",
                List.of(hubRef("hub-skill", "src/"), hubRef("recipe-skill", null)));

        // The recipe's own skills stay recipe-bound; the inherited one is a
        // regular (clearable) activation and keeps its invocation args.
        assertThat(defaults.getValue()).containsExactly("recipe-skill");
        List<ActiveSkillRefEmbedded> active = capturedReplacement("doer-3");
        assertThat(active).extracting(ActiveSkillRefEmbedded::getName).containsExactly("recipe-skill", "hub-skill");
        assertThat(active.get(0).isFromRecipe()).isTrue();
        assertThat(active.get(1).isFromRecipe()).isFalse();
        assertThat(active.get(1).getArgs()).isEqualTo("src/");
        assertThat(worker.getActiveSkills()).isEqualTo(active);
    }

    @Test
    @SuppressWarnings("unchecked")
    void spawn_whitelistRefusedHubSkill_isReportedNotDroppedSilently() {
        stubRecipe("benjy-do-coding", List.of(), List.of("allowed"));
        ThinkProcessDocument worker = new ThinkProcessDocument();
        worker.setId("doer-4");
        var defaults = org.mockito.ArgumentCaptor.forClass(List.class);
        var whitelist = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
        when(thinkProcessService.create(
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
                        any(),
                        any(),
                        any(),
                        defaults.capture(),
                        whitelist.capture()))
                .thenReturn(worker);

        BenjyWorkerSpawner.DoerSpawn doer = spawner.spawn(
                parent(),
                "benjy-do-coding",
                Map.of(),
                "item",
                List.of(hubRef("refused", null), hubRef("allowed", null)));

        assertThat(doer.droppedSkills()).containsExactly("refused");
        assertThat(defaults.getValue()).isEmpty();
        assertThat(capturedReplacement("doer-4"))
                .extracting(ActiveSkillRefEmbedded::getName)
                .containsExactly("allowed");
    }

    @Test
    void spawn_withoutRecipeWhitelist_passesEveryInheritedSkill() {
        stubRecipe("benjy-do-coding", List.of(), null);
        ThinkProcessDocument worker = new ThinkProcessDocument();
        worker.setId("doer-5");
        var defaults = org.mockito.ArgumentCaptor.forClass(List.class);
        var whitelist = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
        when(thinkProcessService.create(
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
                        any(),
                        any(),
                        any(),
                        defaults.capture(),
                        whitelist.capture()))
                .thenReturn(worker);

        BenjyWorkerSpawner.DoerSpawn doer = spawner.spawn(
                parent(), "benjy-do-coding", Map.of(), "item", List.of(hubRef("a", null), hubRef("b", null)));

        assertThat(doer.droppedSkills()).isEmpty();
        assertThat(capturedReplacement("doer-5"))
                .extracting(ActiveSkillRefEmbedded::getName)
                .containsExactly("a", "b");
    }
}
