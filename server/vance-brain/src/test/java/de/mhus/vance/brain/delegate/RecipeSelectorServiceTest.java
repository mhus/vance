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
 * Unit tests for {@link RecipeSelectorService}. Routing has two stages:
 * a single trigger-keyword hit routes deterministically (no LLM); zero
 * or multiple hits fall through to a semantic LLM call (via
 * {@link LightLlmService} with the {@code recipe-selector} recipe) over
 * the full inventory. There is deliberately NO blind recipe-name match —
 * a content word equal to a recipe name (e.g. "python" in "a mindmap
 * about python") must not hijack routing.
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
    void goalNamesRecipeAsIntent_noTrigger_routesToLlm() {
        // "nutze Marvin" reads like a routing intent, but the recipes
        // carry no trigger keywords — so there is no cheap deterministic
        // signal and the semantic LLM stage decides. The old blind
        // recipe-NAME match is gone: naming a recipe no longer short-
        // circuits routing.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stub("marvin", "marvin"),
                stub("essay-pipeline", "marvin")));
        whenLlmReturns(Map.of(
                "decision", "MATCH",
                "recipe", "marvin",
                "rationale", "user explicitly asked for marvin"));

        RecipeSelectorService.Result r = selector.select(caller,
                "nutze Marvin um die Notizen zu sortieren");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("marvin");
        verify(lightLlm).callForJson(any()); // routed through the LLM, not a blind name match
    }

    @Test
    void contentWordMatchingRecipeName_doesNotHijackRouting() {
        // The mindmap bug: a goal that MENTIONS "python" as subject
        // content ("a mindmap about python, java, scala") must NOT route
        // to the `python` recipe by a blind name match. With that match
        // gone, the semantic LLM decides — here it picks a document-
        // authoring recipe, and the python recipe is never auto-selected
        // from the content word.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stub("python", "ford"),
                stub("creator", "ford"),
                stub("default", "ford")));
        whenLlmReturns(Map.of(
                "decision", "MATCH",
                "recipe", "creator",
                "rationale", "authoring a mindmap document, not python execution"));

        RecipeSelectorService.Result r = selector.select(caller,
                "erstelle eine mindmap über programmiersprachen: python, java, scala");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName())
                .as("content word 'python' must not blind-route to the python recipe")
                .isEqualTo("creator");
        verify(lightLlm).callForJson(any());
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
    void noTrigger_routesToLlm_matchUsed() {
        // Zero trigger hits no longer short-circuits to NONE — the
        // semantic stage runs over the full inventory and a MATCH is used.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("marvin", "marvin", List.of("deep think")),
                stub("summary", "ford")));
        whenLlmReturns(Map.of(
                "decision", "MATCH",
                "recipe", "summary",
                "rationale", "a plain summary — general worker fits"));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir eine zusammenfassung");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("summary");
        verify(lightLlm).callForJson(any());
    }

    @Test
    void noTrigger_llmReturnsNone_triggerObservedFalse() {
        // No trigger fired and the LLM finds no fitting recipe. The NONE
        // must carry triggerObserved=false so the caller falls back to the
        // `default` recipe (which has doc_write), not routing.fallback.recipe.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("marvin", "marvin", List.of("deep think")),
                stub("summary", "ford")));
        whenLlmReturns(Map.of(
                "decision", "NONE",
                "recipe", "",
                "rationale", "no existing recipe fits"));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir eine zusammenfassung");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.triggerObserved())
                .as("no trigger fired → NONE routes to the default recipe fallback")
                .isFalse();
        verify(lightLlm).callForJson(any());
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
                false, // listed
                null, // title
                java.util.List.of(),
                null, // postCompletionHook
                RecipeSource.PROJECT);
    }
}
