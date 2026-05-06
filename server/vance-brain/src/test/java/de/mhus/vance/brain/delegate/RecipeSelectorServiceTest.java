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
 * Unit tests for {@link RecipeSelectorService}. Stubs the LLM via a
 * scripted {@link ChatModel} that returns prepared JSON, then
 * verifies the parse, existence-check and NONE-fallback paths.
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

        EngineCatalog catalog = new EngineCatalog();
        catalog.load();
        chatModel = new ScriptedChatModel();
        AiChat scriptedAiChat = mock(AiChat.class);
        when(scriptedAiChat.chatModel()).thenReturn(chatModel);

        // Override buildChat() to skip the credential / cascade
        // chain — production builds an AiChat from the project
        // settings; here we return the scripted stub directly.
        selector = new RecipeSelectorService(
                JsonMapper.builder().build(),
                catalog,
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

    @Test
    void noRecipesAvailable_returnsNoneWithoutLlmCall() {
        when(recipeLoader.listAll(anyString(), any())).thenReturn(List.of());

        RecipeSelectorService.Result r = selector.select(caller, "do something");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.recipeName()).isNull();
        assertThat(r.rationale()).contains("no recipes available");
    }

    @Test
    void emptyTaskDescription_returnsNone() {
        RecipeSelectorService.Result r = selector.select(caller, "   ");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("empty task description");
    }

    @Test
    void llmReturnsMatch_resolvesToRecipe() {
        when(recipeLoader.listAll(anyString(), any()))
                .thenReturn(List.of(stub("essay-pipeline", "marvin")));
        chatModel.script(List.of("""
                {
                  "decision": "MATCH",
                  "recipe": "essay-pipeline",
                  "rationale": "user asks for an essay"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller,
                "schreib mir ein essay über depressive roboter");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.MATCH);
        assertThat(r.recipeName()).isEqualTo("essay-pipeline");
        assertThat(r.engineName()).isEqualTo("marvin");
        assertThat(r.rationale()).contains("user asks for an essay");
    }

    @Test
    void llmReturnsNone_propagatedAsNone() {
        when(recipeLoader.listAll(anyString(), any()))
                .thenReturn(List.of(stub("essay-pipeline", "marvin")));
        chatModel.script(List.of("""
                {
                  "decision": "NONE",
                  "recipe": null,
                  "rationale": "user goal too ambiguous"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller, "do something");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.recipeName()).isNull();
        assertThat(r.rationale()).contains("ambiguous");
    }

    @Test
    void llmHallucinatesRecipeName_caughtAndReturnedAsNone() {
        when(recipeLoader.listAll(anyString(), any()))
                .thenReturn(List.of(stub("essay-pipeline", "marvin")));
        chatModel.script(List.of("""
                {
                  "decision": "MATCH",
                  "recipe": "fabricated-recipe",
                  "rationale": "looks plausible"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller, "task");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale())
                .contains("unknown recipe 'fabricated-recipe'");
    }

    @Test
    void llmReturnsMalformedJson_returnsNoneWithDiagnostic() {
        when(recipeLoader.listAll(anyString(), any()))
                .thenReturn(List.of(stub("essay-pipeline", "marvin")));
        chatModel.script(List.of("not even JSON"));

        RecipeSelectorService.Result r = selector.select(caller, "task");

        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("no JSON object");
    }

    @Test
    void slartGeneratedRecipes_excludedFromInventory() {
        when(recipeLoader.listAll(anyString(), any()))
                .thenReturn(List.of(
                        stub("essay-pipeline", "marvin"),
                        stub("_slart/abc/x", "marvin")));
        chatModel.script(List.of("""
                {
                  "decision": "MATCH",
                  "recipe": "_slart/abc/x",
                  "rationale": "n/a"
                }
                """));

        RecipeSelectorService.Result r = selector.select(caller, "task");

        // _slart/* recipes are filtered out of the inventory the
        // selector sees, so picking one comes back as unknown.
        assertThat(r.decision()).isEqualTo(RecipeSelectorService.Result.Decision.NONE);
        assertThat(r.rationale()).contains("_slart/abc/x");
    }

    // ──────────────────── helpers ────────────────────

    private static ResolvedRecipe stub(String name, String engine) {
        return new ResolvedRecipe(
                name,
                "stub recipe " + name,
                engine,
                java.util.Map.of(),
                null, null,
                PromptMode.APPEND,
                null,
                java.util.List.of(),
                java.util.List.of(),
                java.util.Map.of(),
                java.util.List.of(),
                null,
                false,
                java.util.List.of(),
                RecipeSource.PROJECT);
    }

    private static class ScriptedChatModel implements ChatModel {
        private final java.util.Deque<String> responses = new java.util.ArrayDeque<>();

        void script(List<String> entries) {
            responses.clear();
            responses.addAll(entries);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("ScriptedChatModel: no responses");
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responses.pop()))
                    .build();
        }
    }
}
