package de.mhus.vance.brain.frankie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.PostCompletionHookConfig;
import de.mhus.vance.brain.recipe.PostCompletionHookTrigger;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Focused unit tests for {@link FrankiePostCompletionHookHandler}.
 * Drives the spawn decision through the gate matrix without booting
 * the FrankieEngine — see {@code planning/frankie-post-completion-hook.md}.
 */
class FrankiePostCompletionHookHandlerTest {

    private ThinkProcessService thinkProcessService;
    private RecipeResolver recipeResolver;
    private ActionExecutorRegistry actionRegistry;
    private PromptTemplateRenderer templateRenderer;
    private ChatMessageService chatMessageService;

    private FrankiePostCompletionHookHandler handler;

    private ThinkProcessDocument worker;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        recipeResolver = mock(RecipeResolver.class);
        actionRegistry = mock(ActionExecutorRegistry.class);
        templateRenderer = mock(PromptTemplateRenderer.class);
        chatMessageService = mock(ChatMessageService.class);

        handler = new FrankiePostCompletionHookHandler(
                thinkProcessService, recipeResolver, actionRegistry,
                templateRenderer, chatMessageService);

        worker = new ThinkProcessDocument();
        worker.setId("worker-1");
        worker.setTenantId("tenant-x");
        worker.setProjectId("proj-1");
        worker.setSessionId("sess-1");
        worker.setName("chat");
        worker.setRecipeName("coding");
        worker.setPostCompletionHookRounds(0);

        lenient().when(chatMessageService.activeHistory(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(templateRenderer.render(any(), any()))
                .thenAnswer(inv -> "rendered: " + inv.getArgument(0));
        lenient().when(thinkProcessService.incrementPostCompletionHookRounds(any()))
                .thenReturn(1);
        lenient().when(actionRegistry.execute(any(), any(), any()))
                .thenReturn(ActionResult.scheduled("hook-process-id"));
    }

    @Test
    void noRecipeOnProcess_noSpawn() {
        worker.setRecipeName(null);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
        verify(thinkProcessService, never()).incrementPostCompletionHookRounds(any());
    }

    @Test
    void recipeWithoutHookConfig_noSpawn() {
        givenWorkerRecipeWithHook(null);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void naturalStop_withConfig_spawnsHookProcess() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenWorkerRecipeWithHook(cfg);
        givenHookRecipeExists("code-review", null);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isTrue();
        ArgumentCaptor<TriggerAction> actionCap = ArgumentCaptor.forClass(TriggerAction.class);
        ArgumentCaptor<TriggerContext> ctxCap = ArgumentCaptor.forClass(TriggerContext.class);
        verify(actionRegistry).execute(actionCap.capture(), ctxCap.capture(), eq(TriggerKind.TOOL));
        TriggerAction.Recipe action = (TriggerAction.Recipe) actionCap.getValue();
        assertThat(action.recipe()).isEqualTo("code-review");
        assertThat(action.processName()).isEqualTo("chat_hook_0");
        assertThat(action.goal()).startsWith("rendered: ");
        assertThat(action.initialMessage()).startsWith("rendered: ");
        assertThat(ctxCap.getValue()).isInstanceOf(TriggerContext.Sessioned.class);
        TriggerContext.Sessioned sessioned = (TriggerContext.Sessioned) ctxCap.getValue();
        assertThat(sessioned.parentProcessId()).isEqualTo("worker-1");
        assertThat(sessioned.parentSessionId()).isEqualTo("sess-1");
        verify(thinkProcessService).incrementPostCompletionHookRounds("worker-1");
    }

    @Test
    void triggerTerminate_doesNotFireOnNaturalStop() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.TERMINATE, 1, null);
        givenWorkerRecipeWithHook(cfg);
        givenHookRecipeExists("code-review", null);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), /*naturalStop*/ true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void triggerNaturalStop_doesNotFireOnTerminate() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenWorkerRecipeWithHook(cfg);
        givenHookRecipeExists("code-review", null);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), /*naturalStop*/ false);

        assertThat(spawned).isFalse();
    }

    @Test
    void triggerBoth_firesOnBothPaths() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.BOTH, 5, null);
        givenWorkerRecipeWithHook(cfg);
        givenHookRecipeExists("code-review", null);

        assertThat(handler.maybeSpawn(worker, "done.", List.of(), true)).isTrue();
        worker.setPostCompletionHookRounds(1);
        assertThat(handler.maybeSpawn(worker, "done.", List.of(), false)).isTrue();
    }

    @Test
    void roundCapReached_noSpawn() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenWorkerRecipeWithHook(cfg);
        givenHookRecipeExists("code-review", null);
        worker.setPostCompletionHookRounds(1);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void maxRoundsZero_disablesHook() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 0, null);
        givenWorkerRecipeWithHook(cfg);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void inboxAlreadyCarriesProcessEvent_suppressesSpawn() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 3, null);
        givenWorkerRecipeWithHook(cfg);
        givenHookRecipeExists("code-review", null);

        SteerMessage.ProcessEvent evt = new SteerMessage.ProcessEvent(
                Instant.now(), null, "some-child", ProcessEventType.DONE,
                "summary", Map.of(), "evt-1", null);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(evt), true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void hookRecipeMissing_logsAndSkips() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "missing-recipe", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenWorkerRecipeWithHook(cfg);
        when(recipeResolver.resolve(any(), any(), eq("missing-recipe")))
                .thenReturn(Optional.empty());

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void hookRecipeWrongEngine_logsAndSkips() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenWorkerRecipeWithHook(cfg);
        when(recipeResolver.resolve(any(), any(), eq("code-review")))
                .thenReturn(Optional.of(stubRecipe("code-review", "marvin", null)));

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
        verify(actionRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void hookRecipeWithItsOwnHook_refusesTransitiveLoop() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenWorkerRecipeWithHook(cfg);
        PostCompletionHookConfig nested = new PostCompletionHookConfig(
                "deeper-review", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenHookRecipeExists("code-review", nested);

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
    }

    @Test
    void executorFailure_swallowedAsNoSpawn() {
        PostCompletionHookConfig cfg = new PostCompletionHookConfig(
                "code-review", PostCompletionHookTrigger.NATURAL_STOP, 1, null);
        givenWorkerRecipeWithHook(cfg);
        givenHookRecipeExists("code-review", null);
        when(actionRegistry.execute(any(), any(), any()))
                .thenReturn(ActionResult.failure(
                        de.mhus.vance.brain.action.ActionOutcome.TECHNICAL_ERROR,
                        "boom",
                        null));

        boolean spawned = handler.maybeSpawn(worker, "done.", List.of(), true);

        assertThat(spawned).isFalse();
    }

    // ──────────────────── Helpers ────────────────────

    private void givenWorkerRecipeWithHook(@Nullable PostCompletionHookConfig hookCfg) {
        when(recipeResolver.resolve(eq("tenant-x"), eq("proj-1"), eq("coding")))
                .thenReturn(Optional.of(stubRecipe("coding", "frankie", hookCfg)));
    }

    private void givenHookRecipeExists(
            String hookRecipeName, @Nullable PostCompletionHookConfig nestedCfg) {
        lenient().when(recipeResolver.resolve(eq("tenant-x"), eq("proj-1"), eq(hookRecipeName)))
                .thenReturn(Optional.of(stubRecipe(hookRecipeName, "frankie", nestedCfg)));
    }

    private static ResolvedRecipe stubRecipe(
            String name, String engine, @Nullable PostCompletionHookConfig hookCfg) {
        return new ResolvedRecipe(
                name, "stub", engine,
                Map.of(),
                null, PromptMode.APPEND,
                null,
                List.of(), List.of(), List.of(),
                Map.of(), Map.of(),
                List.of(), null, List.of(),
                false, false, false, null, List.of(),
                hookCfg,
                RecipeSource.RESOURCE);
    }
}
