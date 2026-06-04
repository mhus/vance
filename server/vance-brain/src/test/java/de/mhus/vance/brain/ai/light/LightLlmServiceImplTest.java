package de.mhus.vance.brain.ai.light;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link LightLlmServiceImpl}. Mocks the recipe /
 * template / chat infrastructure so the LLM behaviour can be
 * scripted deterministically.
 */
class LightLlmServiceImplTest {

    private static final String TENANT = "acme";

    private RecipeLoader recipeLoader;
    private PromptTemplateRenderer templateRenderer;
    private SettingService settingService;
    private AiModelResolver aiModelResolver;
    private AiModelService aiModelService;
    private ScriptedChatModel chatModel;
    private LightLlmServiceImpl service;

    @BeforeEach
    void setUp() {
        recipeLoader = mock(RecipeLoader.class);
        templateRenderer = mock(PromptTemplateRenderer.class);
        settingService = mock(SettingService.class);
        aiModelResolver = mock(AiModelResolver.class);
        aiModelService = mock(AiModelService.class);
        // Real MetricService backed by an in-memory registry — keeps
        // the impl exercised without per-test mock setup. Mocking it
        // would only hide drift between method shapes.
        MetricService metricService = new MetricService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        chatModel = new ScriptedChatModel();
        AiChat scriptedChat = mock(AiChat.class);
        when(scriptedChat.chatModel()).thenReturn(chatModel);
        when(aiModelService.createChat(any(ChatBehavior.class), any(AiChatOptions.class)))
                .thenReturn(scriptedChat);
        when(aiModelResolver.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(AiModelResolver.Resolved.direct("openai", "gpt-4o-mini"));
        when(settingService.getDecryptedPasswordCascade(any(), any(), any(), any()))
                .thenReturn("fake-api-key");
        when(settingService.getBooleanValueCascade(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(4));
        when(templateRenderer.render(any(), any())).thenReturn("rendered system prompt");

        service = new LightLlmServiceImpl(
                recipeLoader, templateRenderer, settingService,
                aiModelResolver, aiModelService, JsonMapper.builder().build(),
                metricService,
                org.mockito.Mockito.mock(de.mhus.vance.shared.audit.AuditService.class));
    }

    // ──────────────────── extractJson static helper ────────────────────

    @Test
    void extractJson_returnsEmpty_forNullOrBlank() {
        assertThat(LightLlmServiceImpl.extractJson(null)).isEmpty();
        assertThat(LightLlmServiceImpl.extractJson("   ")).isEmpty();
    }

    @Test
    void extractJson_stripsMarkdownFence() {
        String input = "```json\n{\"a\":1}\n```";
        assertThat(LightLlmServiceImpl.extractJson(input)).isEqualTo("{\"a\":1}");
    }

    @Test
    void extractJson_fallsBackToOutermostBraces() {
        String input = "Here is your answer:\n{\"foo\": \"bar\"}\nAnything else?";
        assertThat(LightLlmServiceImpl.extractJson(input)).isEqualTo("{\"foo\": \"bar\"}");
    }

    // ──────────────────── Request validation ────────────────────

    @Test
    void call_throws_whenRecipeNameMissing() {
        LightLlmRequest req = LightLlmRequest.builder()
                .userPrompt("hello")
                .tenantId(TENANT)
                .build();
        assertThatThrownBy(() -> service.call(req))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("recipeName");
    }

    @Test
    void call_throws_whenUserPromptBlank() {
        LightLlmRequest req = LightLlmRequest.builder()
                .recipeName("how-do-i")
                .userPrompt("   ")
                .tenantId(TENANT)
                .build();
        assertThatThrownBy(() -> service.call(req))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("userPrompt");
    }

    @Test
    void call_throws_whenTenantIdMissing() {
        LightLlmRequest req = LightLlmRequest.builder()
                .recipeName("how-do-i")
                .userPrompt("hello")
                .build();
        assertThatThrownBy(() -> service.call(req))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("tenantId");
    }

    // ──────────────────── Recipe loading + internal-flag enforcement ────────────────────

    @Test
    void call_throws_whenRecipeNotFound() {
        when(recipeLoader.load(any(), any(), any())).thenReturn(Optional.empty());
        LightLlmRequest req = baseRequest();
        assertThatThrownBy(() -> service.call(req))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("recipe not found");
    }

    @Test
    void call_throws_whenRecipeNotMarkedInternal() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", /*internal*/ false)));
        LightLlmRequest req = baseRequest();
        assertThatThrownBy(() -> service.call(req))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("internal:true");
    }

    @Test
    void call_throws_whenRecipeHasNoPromptPrefix() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipeWithoutPrompt("how-do-i")));
        LightLlmRequest req = baseRequest();
        assertThatThrownBy(() -> service.call(req))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("promptPrefix");
    }

    // ──────────────────── Raw call ────────────────────

    @Test
    void call_returnsRawLlmText_whenRecipeIsInternal() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", true)));
        chatModel.script(List.of("the answer is 42"));
        LightLlmRequest req = baseRequest();
        String text = service.call(req);
        assertThat(text).isEqualTo("the answer is 42");
        assertThat(chatModel.invocations).isEqualTo(1);
    }

    // ──────────────────── Schema-validated call (happy + retries) ────────────────────

    @Test
    void callForJson_returnsMap_onFirstValidReply() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", true)));
        chatModel.script(List.of("{\"loaded\": {\"name\": \"x\"}}"));

        Map<String, Object> result = service.callForJson(jsonRequest());
        assertThat(result).containsEntry("loaded", Map.of("name", "x"));
        assertThat(chatModel.invocations).isEqualTo(1);
    }

    @Test
    void callForJson_succeedsOnSecondAttempt_afterInvalidJson() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", true)));
        chatModel.script(List.of(
                "this isn't json at all, sorry",
                "{\"hint\": \"no match\"}"));

        Map<String, Object> result = service.callForJson(jsonRequest());
        assertThat(result).containsEntry("hint", "no match");
        assertThat(chatModel.invocations).isEqualTo(2);
    }

    @Test
    void callForJson_throwsSchemaValidationException_afterMaxAttempts() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", true)));
        chatModel.script(List.of("not json", "still not json", "nope"));

        LightLlmRequest req = LightLlmRequest.builder()
                .recipeName("how-do-i")
                .userPrompt("test")
                .tenantId(TENANT)
                .maxAttempts(3)
                .build();

        assertThatThrownBy(() -> service.callForJson(req))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("Schema not satisfied");
        assertThat(chatModel.invocations).isEqualTo(3);
    }

    @Test
    void callForJson_extractsJson_fromMarkdownFencedReply() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", true)));
        chatModel.script(List.of("```json\n{\"answer\": 42}\n```"));

        Map<String, Object> result = service.callForJson(jsonRequest());
        assertThat(result).containsEntry("answer", 42);
    }

    @Test
    void callForJson_validatesAgainstSchema_andRejectsMissingRequired() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", true)));
        // First reply: valid JSON but missing required 'loaded' property
        // Second reply: contains 'loaded' → schema-valid
        chatModel.script(List.of(
                "{\"unrelated\": 1}",
                "{\"loaded\": \"x\"}"));

        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("loaded"));

        LightLlmRequest req = LightLlmRequest.builder()
                .recipeName("how-do-i")
                .userPrompt("test")
                .schema(schema)
                .tenantId(TENANT)
                .build();

        Map<String, Object> result = service.callForJson(req);
        assertThat(result).containsKey("loaded");
        assertThat(chatModel.invocations).isEqualTo(2);
    }

    @Test
    void callForJson_doesNotInvokeLlm_whenRecipeMissing() {
        when(recipeLoader.load(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.callForJson(jsonRequest()))
                .isInstanceOf(LightLlmException.class);
        assertThat(chatModel.invocations).isZero();
        verify(aiModelService, never()).createChat(any(ChatBehavior.class), any(AiChatOptions.class));
    }

    @Test
    void call_throws_whenMasterSwitchDisabled() {
        when(settingService.getBooleanValueCascade(
                any(), any(), any(), eq("lightllm.enabled"), anyBoolean()))
                .thenReturn(false);
        assertThatThrownBy(() -> service.call(baseRequest()))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("disabled");
        verify(recipeLoader, never()).load(any(), any(), any());
    }

    @Test
    void callForJson_propagatesLlmProviderError() {
        when(recipeLoader.load(any(), any(), any()))
                .thenReturn(Optional.of(stubRecipe("how-do-i", true)));
        chatModel.failNext(new RuntimeException("provider 5xx"));

        assertThatThrownBy(() -> service.callForJson(jsonRequest()))
                .isInstanceOf(LightLlmException.class)
                .hasMessageContaining("LLM call failed");
        verify(aiModelService, times(1))
                .createChat(any(ChatBehavior.class), any(AiChatOptions.class));
    }

    // ──────────────────── Helpers ────────────────────

    private static LightLlmRequest baseRequest() {
        return LightLlmRequest.builder()
                .recipeName("how-do-i")
                .userPrompt("test prompt")
                .tenantId(TENANT)
                .build();
    }

    private static LightLlmRequest jsonRequest() {
        return LightLlmRequest.builder()
                .recipeName("how-do-i")
                .userPrompt("test prompt")
                .tenantId(TENANT)
                .build();
    }

    private static ResolvedRecipe stubRecipe(String name, boolean internal) {
        return new ResolvedRecipe(
                name,
                "stub recipe " + name,
                "ford",
                Map.of("model", "default:fast"),
                "system prompt template {{ intent }}",
                PromptMode.APPEND,
                null,
                List.of(), List.of(), List.of(),
                Map.of(), Map.of(),
                List.of(),
                null,
                List.of(),
                false,        // locked
                internal,     // internal
                List.of(),
                RecipeSource.PROJECT);
    }

    private static ResolvedRecipe stubRecipeWithoutPrompt(String name) {
        return new ResolvedRecipe(
                name,
                "stub recipe " + name,
                "ford",
                Map.of("model", "default:fast"),
                null,         // promptPrefix missing
                PromptMode.APPEND,
                null,
                List.of(), List.of(), List.of(),
                Map.of(), Map.of(),
                List.of(),
                null,
                List.of(),
                false,
                true,         // internal
                List.of(),
                RecipeSource.PROJECT);
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> responses = new ArrayDeque<>();
        private RuntimeException nextError;
        int invocations;

        void script(List<String> entries) {
            responses.clear();
            responses.addAll(entries);
        }

        void failNext(RuntimeException error) {
            this.nextError = error;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            invocations++;
            if (nextError != null) {
                RuntimeException e = nextError;
                nextError = null;
                throw e;
            }
            if (responses.isEmpty()) {
                throw new IllegalStateException("ScriptedChatModel: no responses scripted");
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responses.pop()))
                    .build();
        }
    }
}
