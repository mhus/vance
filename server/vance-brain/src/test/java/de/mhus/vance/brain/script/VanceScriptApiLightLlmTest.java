package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmJsonAnswer;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the {@code vance.llm.*} surface of {@link VanceScriptApi}
 * against a mocked {@link LightLlmService}: scope cascading, argument
 * pass-through, error mapping, and the null-service degrade path.
 */
class VanceScriptApiLightLlmTest {

    private LightLlmService lightLlmService;
    private VanceScriptApi api;

    @BeforeEach
    void setUp() {
        lightLlmService = mock(LightLlmService.class);
        api = new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), null, null, null, null, lightLlmService);
    }

    @Test
    void call_passesRecipePromptVarsAndScope_throughToService() {
        when(lightLlmService.call(any(LightLlmRequest.class))).thenReturn("hello");

        String result = api.llm.call("title-gen", "Generate", Map.of("topic", "gRPC"));

        assertThat(result).isEqualTo("hello");
        ArgumentCaptor<LightLlmRequest> captor = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlmService).call(captor.capture());
        LightLlmRequest req = captor.getValue();
        assertThat(req.getRecipeName()).isEqualTo("title-gen");
        assertThat(req.getUserPrompt()).isEqualTo("Generate");
        assertThat(req.getPebbleVars()).containsEntry("topic", "gRPC");
        assertThat(req.getTenantId()).isEqualTo("acme");
        assertThat(req.getProjectId()).isEqualTo("proj");
        assertThat(req.getProcessId()).isEqualTo("proc");
        assertThat(req.getSchema()).isNull();
    }

    @Test
    void call_withoutVars_overloadDelegates() {
        when(lightLlmService.call(any(LightLlmRequest.class))).thenReturn("ok");

        api.llm.call("title-gen", "Generate");

        ArgumentCaptor<LightLlmRequest> captor = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlmService).call(captor.capture());
        assertThat(captor.getValue().getPebbleVars()).isNull();
    }

    @Test
    void callForJson_returnsParsedMap_andPassesScope() {
        when(lightLlmService.callForJson(any(LightLlmRequest.class)))
                .thenReturn(Map.of("important", true, "summary", "Rechnung von xyz"));

        Map<String, Object> result = api.llm.callForJson(
                "mail-rate", "Bewerte.", Map.of("from", "a@b.c"));

        assertThat(result)
                .containsEntry("important", true)
                .containsEntry("summary", "Rechnung von xyz");
        ArgumentCaptor<LightLlmRequest> captor = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlmService).callForJson(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("acme");
        assertThat(captor.getValue().getProjectId()).isEqualTo("proj");
    }

    @Test
    @SuppressWarnings("unchecked")
    void callForJsonWithModel_nestsTheReplyUnderResult_soFieldsCannotCollide() {
        when(lightLlmService.callForJsonWithModel(any(LightLlmRequest.class)))
                .thenReturn(new LightLlmJsonAnswer(
                        // A reply that carries its own `model` field is the
                        // case the nesting exists for.
                        Map.of("title", "Rat beschliesst Plan", "model", "sedan"),
                        "openai:deepseek-v4-pro"));

        Map<String, Object> result = api.llm.callForJsonWithModel(
                "article-translate", "Uebersetze.", Map.of("targetLang", "de"));

        assertThat(result).containsOnlyKeys("result", "model");
        assertThat(result).containsEntry("model", "openai:deepseek-v4-pro");
        assertThat((Map<String, Object>) result.get("result"))
                .as("the caller's own 'model' field must survive untouched")
                .containsEntry("model", "sedan")
                .containsEntry("title", "Rat beschliesst Plan");
    }

    @Test
    void callForJsonWithModel_unknownModel_staysNull_ratherThanBeingInvented() {
        when(lightLlmService.callForJsonWithModel(any(LightLlmRequest.class)))
                .thenReturn(new LightLlmJsonAnswer(Map.of("title", "x"), null));

        Map<String, Object> result = api.llm.callForJsonWithModel("article-translate", "x");

        // A consumer writing this into an archive must be able to tell
        // "unknown" from a plausible default it never actually used.
        assertThat(result).containsKey("model");
        assertThat(result.get("model")).isNull();
    }

    @Test
    void callForJsonWithModel_schemaValidationExhausted_mapsToScriptHostException() {
        when(lightLlmService.callForJsonWithModel(any(LightLlmRequest.class)))
                .thenThrow(new SchemaValidationException(2, Map.of("a", "b"), "shape mismatch"));

        assertThatThrownBy(() -> api.llm.callForJsonWithModel("article-translate", "x", null))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("article-translate")
                .hasMessageContaining("schema validation");
    }

    @Test
    void callForJson_schemaValidationExhausted_mapsToScriptHostException() {
        when(lightLlmService.callForJson(any(LightLlmRequest.class)))
                .thenThrow(new SchemaValidationException(
                        3, Map.of("foo", "bar"), "shape mismatch"));

        assertThatThrownBy(() -> api.llm.callForJson("mail-rate", "x", null))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("mail-rate")
                .hasMessageContaining("schema validation");
    }

    @Test
    void call_lightLlmException_mapsToScriptHostException() {
        when(lightLlmService.call(any(LightLlmRequest.class)))
                .thenThrow(new LightLlmException("recipe not internal"));

        assertThatThrownBy(() -> api.llm.call("public-recipe", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("public-recipe")
                .hasMessageContaining("recipe not internal");
    }

    @Test
    void call_emptyRecipeName_rejected() {
        assertThatThrownBy(() -> api.llm.call("", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("recipeName");
    }

    @Test
    void call_nullPrompt_rejected() {
        assertThatThrownBy(() -> api.llm.call("mail-rate", null))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("userPrompt");
    }

    @Test
    void call_noTenantScope_rejected() {
        VanceScriptApi noTenant = new VanceScriptApi(
                contextTools(null, null, null, null, null),
                null, Set.of(), null, null, null, null, lightLlmService);

        assertThatThrownBy(() -> noTenant.llm.call("mail-rate", "x"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void apiWithoutLightLlmService_hasNullLlmField() {
        VanceScriptApi noLlm = new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), null, null, null, null, null);

        assertThat(noLlm.llm).isNull();
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static ContextToolsApi contextTools(
            String tenant, String project, String session, String process, String user) {
        ContextToolsApi tools = mock(ContextToolsApi.class);
        when(tools.scope()).thenReturn(
                new ToolInvocationContext(tenant, project, session, process, user));
        return tools;
    }
}
