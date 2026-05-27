package de.mhus.vance.brain.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the trigger-gated {@link RecipeSelectorService}. The
 * service runs a deterministic pre-check (recipe-name word-boundary +
 * trigger-keyword substring) BEFORE any LLM call. The LLM stage —
 * now routed through {@link LightLlmService} with the
 * {@code recipe-selector} internal recipe — only fires when multiple
 * trigger-matched candidates need disambiguation.
 */
class RecipeSelectorServiceTest {

    private RecipeSelectorService selector;
    private RecipeLoader recipeLoader;
    private LightLlmService lightLlm;
    private ThinkProcessDocument caller;

    @BeforeEach
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        lightLlm = mock(LightLlmService.class);
        selector = new RecipeSelectorService(recipeLoader, lightLlm);

        caller = new ThinkProcessDocument();
        caller.setId("proc-1");
        caller.setTenantId("acme");
        caller.setProjectId("test-project");
        caller.setSessionId("sess-1");
    }

    // ──────────────────── deterministic pre-check ────────────────────

    @Test
    void emptyTaskDescription_returnsNone() {
        RecipeSelectorService.Result r = selector.select(caller, "   ");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("empty task description");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void noRecipesAvailable_returnsNoneWithoutLlm() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of());

        RecipeSelectorService.Result r = selector.select(caller, "do something");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("no recipes available");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void goalContainsRecipeName_directMatchNoLlm() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stub("marvin", "marvin"),
                stub("essay-pipeline", "marvin")));

        RecipeSelectorService.Result r = selector.select(caller,
                "nutze Marvin um die Notizen zu sortieren");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("marvin");
        assertThat(r.engineName()).isEqualTo("marvin");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void longerRecipeNameWinsOverShorter() {
        // Both "analyze" and "deep-analyze" appear as recipes; the
        // goal contains the longer phrase. Longest-match must win
        // so the specific recipe gets picked.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stub("analyze", "ford"),
                stub("deep-analyze", "marvin")));

        RecipeSelectorService.Result r = selector.select(caller,
                "please run a deep-analyze on this code");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("deep-analyze");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void recipeNameMatchRequiresWordBoundary() {
        // Recipe "ford" must NOT match the substring inside "effort".
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stub("ford", "ford")));

        RecipeSelectorService.Result r = selector.select(caller,
                "considerable effort required");

        // No recipe-name match, no trigger keywords on ford stub → NONE.
        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void goalContainsTriggerKeyword_singleMatchNoLlm() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("hactar", "hactar",
                        List.of("hactar", "javascript script"))));

        RecipeSelectorService.Result r = selector.select(caller,
                "Generiere mir bitte ein javascript script zur Verarbeitung");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("hactar");
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void goalWithoutTrigger_returnsNoneWithoutLlm() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("marvin", "marvin",
                        List.of("marvin", "deep think")),
                stubWithTriggers("hactar", "hactar",
                        List.of("hactar", "javascript"))));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir eine zusammenfassung");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("no trigger detected");
        assertThat(r.triggerObserved())
                .as("no trigger keyword fired → caller should fall through "
                        + "to the default recipe, not the configurable fallback")
                .isFalse();
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void llmReturnsNone_isMarkedTriggerObserved() {
        // Trigger fires (two candidates), but the LLM rejects both —
        // caller must spawn the configurable fallback recipe, not
        // the default. The triggerObserved flag carries that.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        whenLlmReturns(Map.of(
                "decision", "NONE",
                "recipe", "",
                "rationale", "neither candidate truly fits"));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir ein essay über depressive roboter");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.triggerObserved()).isTrue();
        assertThat(r.rationale()).contains("neither candidate truly fits");
    }

    @Test
    void internalRecipes_excludedFromInventory() {
        // _slart/*, _* and `internal: true` recipes never appear in
        // the routing inventory — including the selector's own
        // {@code recipe-selector} recipe would create a self-loop.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stub("_slart/abc/x", "marvin")));

        RecipeSelectorService.Result r = selector.select(caller,
                "_slart/abc/x"); // even mentioning the path → no match

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        verify(lightLlm, never()).callForJson(any());
    }

    // ──────────────────── LLM disambiguation ────────────────────

    @Test
    void multipleTriggerMatches_runLlmDisambiguation() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-pipeline", "marvin",
                        List.of("essay")),
                stubWithTriggers("school-essay", "vogon",
                        List.of("essay"))));
        whenLlmReturns(Map.of(
                "decision", "MATCH",
                "recipe", "school-essay",
                "rationale", "school context fits better"));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir ein essay über depressive roboter");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("school-essay");
        assertThat(r.engineName()).isEqualTo("vogon");
    }

    @Test
    void llmReturnsNone_propagatedAsNone() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        whenLlmReturns(Map.of(
                "decision", "NONE",
                "recipe", "",
                "rationale", "user goal too ambiguous"));

        RecipeSelectorService.Result r = selector.select(caller,
                "essay something");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("ambiguous");
    }

    @Test
    void llmHallucinatesRecipeName_caughtAndReturnedAsNone() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        whenLlmReturns(Map.of(
                "decision", "MATCH",
                "recipe", "fabricated-recipe",
                "rationale", "looks plausible"));

        RecipeSelectorService.Result r = selector.select(caller, "essay task");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale())
                .contains("unknown recipe 'fabricated-recipe'");
    }

    @Test
    void llmCallFailure_returnsNoneAfterTrigger() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        when(lightLlm.callForJson(any()))
                .thenThrow(new LightLlmException("provider 503"));

        RecipeSelectorService.Result r = selector.select(caller, "essay task");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.triggerObserved()).isTrue();
        assertThat(r.rationale()).contains("provider 503");
    }

    @Test
    void llmSchemaBudgetExhausted_returnsNoneAfterTriggerWithAttemptCount() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        when(lightLlm.callForJson(any()))
                .thenThrow(new SchemaValidationException(2, Map.of(), "missing 'decision'"));

        RecipeSelectorService.Result r = selector.select(caller, "essay task");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.triggerObserved()).isTrue();
        assertThat(r.rationale()).contains("2 attempts");
    }

    @Test
    void llmDisambiguation_passesCandidatesAndTaskAsPebbleVars() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        whenLlmReturns(Map.of(
                "decision", "MATCH",
                "recipe", "essay-a",
                "rationale", "fits"));

        selector.select(caller, "schreib ein essay");

        ArgumentCaptor<LightLlmRequest> cap = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(cap.capture());
        LightLlmRequest req = cap.getValue();
        assertThat(req.getRecipeName()).isEqualTo(RecipeSelectorService.RECIPE_NAME);
        assertThat(req.getTenantId()).isEqualTo("acme");
        assertThat(req.getProjectId()).isEqualTo("test-project");
        assertThat(req.getProcessId()).isEqualTo("proc-1");
        assertThat(req.getPebbleVars()).containsKey("candidates").containsKey("task");
        assertThat(req.getPebbleVars().get("task")).isEqualTo("schreib ein essay");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> cands =
                (List<Map<String, String>>) req.getPebbleVars().get("candidates");
        assertThat(cands).extracting(m -> m.get("name"))
                .containsExactly("essay-a", "essay-b");
    }

    // ──────────────────── word-boundary helper ────────────────────

    @Test
    void containsAsWord_matchesAtBoundary() {
        assertThat(RecipeSelectorService.containsAsWord("use marvin now", "marvin")).isTrue();
        assertThat(RecipeSelectorService.containsAsWord("marvin", "marvin")).isTrue();
        assertThat(RecipeSelectorService.containsAsWord("Marvin!".toLowerCase(), "marvin")).isTrue();
        assertThat(RecipeSelectorService.containsAsWord("quick-lookup is fast", "quick-lookup")).isTrue();
        assertThat(RecipeSelectorService.containsAsWord("considerable effort", "ford")).isFalse();
        assertThat(RecipeSelectorService.containsAsWord("marvinify", "marvin")).isFalse();
    }

    // ──────────────────── helpers ────────────────────

    private void whenLlmReturns(Map<String, Object> reply) {
        when(lightLlm.callForJson(any())).thenReturn(new LinkedHashMap<>(reply));
    }

    private static ResolvedRecipe stub(String name, String engine) {
        return stubWithTriggers(name, engine, List.of());
    }

    private static ResolvedRecipe stubWithTriggers(
            String name, String engine, List<String> triggerKeywords) {
        return new ResolvedRecipe(
                name,
                "stub recipe " + name,
                engine,
                java.util.Map.of(),
                null,
                PromptMode.APPEND,
                null,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(), // allowedToolsDefer
                java.util.Map.of(), // modes (recipe-base)
                java.util.Map.of(),
                java.util.List.of(),
                null,
                triggerKeywords,
                false,
                false, // internal
                java.util.List.of(),
                RecipeSource.PROJECT);
    }
}
