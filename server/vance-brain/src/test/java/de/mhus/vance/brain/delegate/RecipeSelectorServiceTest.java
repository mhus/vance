package de.mhus.vance.brain.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the trigger-gated {@link RecipeSelectorService}. The
 * service now runs a deterministic pre-check (recipe-name word-boundary
 * + trigger-keyword substring) BEFORE any LLM call. The LLM only fires
 * when multiple trigger-matched candidates need disambiguation.
 */
class RecipeSelectorServiceTest {

    private RecipeSelectorService selector;
    private RecipeLoader recipeLoader;
    private ScriptedChatModel chatModel;
    private ThinkProcessDocument caller;

    @BeforeEach
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        SettingService settingService = mock(SettingService.class);
        AiModelResolver aiModelResolver = mock(AiModelResolver.class);
        AiModelService aiModelService = mock(AiModelService.class);

        chatModel = new ScriptedChatModel();
        AiChat scriptedAiChat = mock(AiChat.class);
        when(scriptedAiChat.chatModel()).thenReturn(chatModel);

        // Override buildChat() to skip the credential / cascade
        // chain — production builds an AiChat from the project
        // settings; here we return the scripted stub directly.
        selector = new RecipeSelectorService(
                JsonMapper.builder().build(),
                recipeLoader,
                settingService,
                aiModelResolver,
                aiModelService) {
            @Override
            AiChat buildChat(ThinkProcessDocument c) {
                return scriptedAiChat;
            }
        };

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
    }

    @Test
    void noRecipesAvailable_returnsNoneWithoutLlm() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of());

        RecipeSelectorService.Result r = selector.select(caller, "do something");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("no recipes available");
        assertThat(chatModel.invocations).isZero();
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
        assertThat(chatModel.invocations).isZero();
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
        assertThat(chatModel.invocations).isZero();
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
        assertThat(chatModel.invocations).isZero();
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
        assertThat(chatModel.invocations).isZero();
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
        assertThat(chatModel.invocations).isZero();
    }

    @Test
    void llmReturnsNone_isMarkedTriggerObserved() {
        // Trigger fires (two candidates), but the LLM rejects both —
        // caller must spawn the configurable fallback recipe, not
        // the default. The triggerObserved flag carries that.
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        chatModel.script(List.of("""
                {
                  "decision": "NONE",
                  "recipe": null,
                  "rationale": "neither candidate truly fits"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir ein essay über depressive roboter");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.triggerObserved()).isTrue();
        assertThat(chatModel.invocations).isEqualTo(1);
    }

    @Test
    void slartGeneratedRecipes_excludedFromInventory() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stub("_slart/abc/x", "marvin")));

        RecipeSelectorService.Result r = selector.select(caller,
                "_slart/abc/x"); // even mentioning the path → no match

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(chatModel.invocations).isZero();
    }

    // ──────────────────── LLM disambiguation ────────────────────

    @Test
    void multipleTriggerMatches_runLlmDisambiguation() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-pipeline", "marvin",
                        List.of("essay")),
                stubWithTriggers("school-essay", "vogon",
                        List.of("essay"))));
        chatModel.script(List.of("""
                {
                  "decision": "MATCH",
                  "recipe": "school-essay",
                  "rationale": "school context fits better"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir ein essay über depressive roboter");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("school-essay");
        assertThat(r.engineName()).isEqualTo("vogon");
        assertThat(chatModel.invocations).isEqualTo(1);
    }

    @Test
    void llmReturnsNone_propagatedAsNone() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        chatModel.script(List.of("""
                {
                  "decision": "NONE",
                  "recipe": null,
                  "rationale": "user goal too ambiguous"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller,
                "essay something");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("ambiguous");
        assertThat(chatModel.invocations).isEqualTo(1);
    }

    @Test
    void llmHallucinatesRecipeName_caughtAndReturnedAsNone() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        chatModel.script(List.of("""
                {
                  "decision": "MATCH",
                  "recipe": "fabricated-recipe",
                  "rationale": "looks plausible"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller, "essay task");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale())
                .contains("unknown recipe 'fabricated-recipe'");
    }

    @Test
    void llmReturnsMalformedJson_returnsNoneWithDiagnostic() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of(
                stubWithTriggers("essay-a", "marvin", List.of("essay")),
                stubWithTriggers("essay-b", "vogon", List.of("essay"))));
        chatModel.script(List.of("not even JSON"));

        RecipeSelectorService.Result r = selector.select(caller, "essay task");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("no JSON object");
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
                java.util.List.of(),
                RecipeSource.PROJECT);
    }

    private static class ScriptedChatModel implements ChatModel {
        private final java.util.Deque<String> responses = new java.util.ArrayDeque<>();
        int invocations;

        void script(List<String> entries) {
            responses.clear();
            responses.addAll(entries);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            invocations++;
            if (responses.isEmpty()) {
                throw new IllegalStateException("ScriptedChatModel: no responses");
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responses.pop()))
                    .build();
        }
    }
}
