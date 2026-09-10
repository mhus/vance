package de.mhus.vance.brain.tools.aimodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tool's value is fidelity: it must report exactly what the engine
 * resolves for this process — the spec as written plus the resolved chain
 * — and never the API key. The tests pin the resolution output for a
 * primary + fallback chain, and that an unbuildable configuration fails
 * with the cause (which names the setting to fix) instead of a guess.
 */
class AiModelCurrentToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "model-sipgate-coding";
    private static final String PROCESS = "proc1";
    private static final String SESSION = "sess1";

    private ThinkProcessService thinkProcessService;
    private SettingService settingService;
    private AiModelCurrentTool tool;
    private ToolInvocationContext ctx;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        settingService = mock(SettingService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        ModelCatalog modelCatalog = mock(ModelCatalog.class);
        when(aiModelService.hasProvider("openai")).thenReturn(true);
        when(aiModelService.hasProvider("gemini")).thenReturn(true);
        when(aiModelService.listProviders()).thenReturn(List.of("openai", "gemini"));
        when(modelCatalog.lookupProvider(any(), any(), any())).thenReturn(Optional.empty());
        AiModelResolver resolver = new AiModelResolver(aiModelService, settingService, modelCatalog);
        tool = new AiModelCurrentTool(thinkProcessService, settingService, resolver);

        process = new ThinkProcessDocument();
        process.setId(PROCESS);
        process.setTenantId(TENANT);
        process.setProjectId(PROJECT);
        process.setSessionId(SESSION);
        process.setRecipeName("creator");
        ctx = new ToolInvocationContext(TENANT, PROJECT, SESSION, PROCESS, "road.runner");
    }

    @Test
    void invoke_reportsSpecAndResolvedChain() {
        process.setEngineParams(
                Map.of("model", "openai:gpt-4o-mini", "fallbackModels", List.of("gemini:gemini-2.5-flash")));
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.of(process));
        stubKey("openai", "sk-primary");
        stubKey("gemini", "sk-fallback");

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        assertThat(out.get("recipe")).isEqualTo("creator");
        assertThat(out.get("spec")).isEqualTo("openai:gpt-4o-mini");
        assertThat(out.get("fallbackSpecs")).isEqualTo(List.of("gemini:gemini-2.5-flash"));

        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) out.get("primary");
        assertThat(primary.get("label")).isEqualTo("primary");
        assertThat(primary.get("provider")).isEqualTo("openai");
        assertThat(primary.get("providerInstance")).isEqualTo("openai");
        assertThat(primary.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(primary.get("fullName")).isEqualTo("openai:gpt-4o-mini");
        assertThat(primary.get("baseUrl")).isNull();
        assertThat(primary.get("keyless")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chain = (List<Map<String, Object>>) out.get("chain");
        assertThat(chain).hasSize(2);
        assertThat(chain.get(1).get("label")).isEqualTo("fallback:gemini:gemini-2.5-flash");
        assertThat(chain.get(1).get("model")).isEqualTo("gemini-2.5-flash");
        // The API key never enters the result — not the value, not a mask.
        assertThat(String.valueOf(out)).doesNotContain("sk-primary");
        assertThat(String.valueOf(out)).doesNotContain("sk-fallback");
    }

    @Test
    void invoke_unresolvableConfig_failsWithTheCause() {
        // A named instance that is declared nowhere — the engine could not
        // build this chat either, and the error names what is missing.
        process.setEngineParams(Map.of("model", "coding-proxy:gpt-5"));
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.of(process));

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no usable model configuration")
                .hasMessageContaining("coding-proxy");
    }

    @Test
    void invoke_missingApiKey_failsNamingTheSetting() {
        process.setEngineParams(Map.of("model", "openai:gpt-4o-mini"));
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.of(process));
        // No key anywhere in the cascade.
        when(settingService.getDecryptedPasswordCascade(any(), any(), any(), anyString()))
                .thenReturn(null);

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ai.provider.openai.apiKey");
    }

    @Test
    void invoke_withoutProcessScope_refused() {
        ToolInvocationContext sessionCtx = new ToolInvocationContext(TENANT, PROJECT, SESSION, null, "road.runner");

        assertThatThrownBy(() -> tool.invoke(Map.of(), sessionCtx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("requires a process scope");
    }

    private void stubKey(String instance, String key) {
        when(settingService.getDecryptedPasswordCascade(
                        eq(TENANT), eq(PROJECT), eq(PROCESS), eq("ai.provider." + instance + ".apiKey")))
                .thenReturn(key);
    }
}
