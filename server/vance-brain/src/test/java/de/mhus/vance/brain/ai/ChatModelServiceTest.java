package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chatmodel.ChatModelDto;
import de.mhus.vance.api.chatmodel.ChatModelListDto;
import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatModelServiceTest {

    private ModelCatalog catalog;
    private AiModelResolver resolver;
    private SettingService settings;
    private ChatModelService service;

    @BeforeEach
    void setUp() {
        catalog = mock(ModelCatalog.class);
        resolver = mock(AiModelResolver.class);
        settings = mock(SettingService.class);
        service = new ChatModelService(catalog, resolver, settings);
        // Direct specs resolve to themselves; the openai wire protocol needs a key.
        when(resolver.resolve(any(), any(), any(), any())).thenAnswer(inv -> {
            String spec = inv.getArgument(0);
            int colon = spec.indexOf(':');
            String instance = spec.substring(0, colon);
            String wire = instance.equals("ollama") ? "ollama" : "openai";
            return new AiModelResolver.Resolved(wire, instance, spec.substring(colon + 1));
        });
    }

    private static ModelInfo model(
            String instance, String name, Set<ModelCapability> caps, ModelInfo.@Nullable Pricing pricing) {
        return new ModelInfo(
                instance,
                name,
                128000,
                8192,
                ModelSize.LARGE,
                caps,
                60,
                2,
                false,
                null,
                pricing,
                OutputTokenParam.MAX_TOKENS,
                Set.of(),
                null);
    }

    private void key(String instance) {
        when(settings.getDecryptedPasswordCascade(eq("acme"), any(), any(), eq("ai.provider." + instance + ".apiKey")))
                .thenReturn("sk-" + instance);
    }

    private void tenantAllowlist(String value) {
        when(settings.getStringValue("acme", SettingService.SCOPE_PROJECT, "_tenant", "chat.allowed-models"))
                .thenReturn(value);
    }

    private void projectAllowlist(String value) {
        when(settings.getStringValue("acme", SettingService.SCOPE_PROJECT, "web", "chat.allowed-models"))
                .thenReturn(value);
    }

    private ThinkProcessDocument process(Map<String, Object> params, Map<String, Object> overrides) {
        ThinkProcessDocument p = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("web")
                .build();
        p.setEngineParams(new HashMap<>(params));
        p.setEngineParamOverrides(new HashMap<>(overrides));
        return p;
    }

    @Test
    void allowlist_unsetEverywhere_meansEverything() {
        assertThat(service.allowlistFor("acme", "web")).isEqualTo("*");
    }

    @Test
    void allowlist_blankProjectValue_fallsBackToTenant() {
        projectAllowlist("  ");
        tenantAllowlist("cortecs:*");

        assertThat(service.allowlistFor("acme", "web")).isEqualTo("cortecs:*");
    }

    @Test
    void allowlist_projectValue_winsOverTenant() {
        projectAllowlist("anthropic:*");
        tenantAllowlist("cortecs:*");

        assertThat(service.allowlistFor("acme", "web")).isEqualTo("anthropic:*");
    }

    @Test
    void list_filtersByAllowlistAndConfiguredInstance_sorted() {
        ModelInfo.Pricing price = new ModelInfo.Pricing("USD", 3.0, 15.0, null, null);
        when(catalog.listAll("acme", "web"))
                .thenReturn(List.of(
                        model("cortecs", "glm-5", Set.of(ModelCapability.THINKING), price),
                        model("anthropic", "claude-sonnet", Set.of(), null),
                        model("cortecs", "deepseek-v4", Set.of(), null),
                        model("gemini", "gemini-pro", Set.of(), null)));
        key("cortecs");
        key("anthropic");
        // gemini has no key → not configured
        tenantAllowlist("cortecs:*, anthropic:claude-*");

        ChatModelListDto list = service.listSelectable("acme", "web");

        assertThat(list.getModels())
                .extracting(ChatModelDto::getSpec)
                .containsExactly("anthropic:claude-sonnet", "cortecs:deepseek-v4", "cortecs:glm-5");
        ChatModelDto glm = list.getModels().get(2);
        assertThat(glm.isThinking()).isTrue();
        assertThat(glm.getPricing()).isNotNull();
        assertThat(glm.getPricing().getInputPerMTok()).isEqualTo(3.0);
        assertThat(glm.getPricing().getOutputPerMTok()).isEqualTo(15.0);
        assertThat(list.getModels().get(0).getPricing()).isNull();
        assertThat(list.getThinkingLevels()).containsExactly("off", "minimal", "low", "medium", "high");
    }

    @Test
    void list_keylessInstance_needsExplicitBaseUrl() {
        when(catalog.listAll("acme", "web")).thenReturn(List.of(model("ollama", "qwen3", Set.of(), null)));

        assertThat(service.listSelectable("acme", "web").getModels()).isEmpty();

        when(settings.getStringValueCascade(eq("acme"), eq("web"), any(), eq("ai.provider.ollama.baseUrl")))
                .thenReturn("http://ollama:11434");

        assertThat(service.listSelectable("acme", "web").getModels())
                .extracting(ChatModelDto::getSpec)
                .containsExactly("ollama:qwen3");
    }

    @Test
    void validate_approvedConfiguredCatalogModel_returnsResolvedSpec() {
        when(catalog.lookup("acme", "web", "cortecs", "glm-5"))
                .thenReturn(Optional.of(model("cortecs", "glm-5", Set.of(), null)));
        key("cortecs");

        ChatModelService.Selection sel = service.validate(process(Map.of(), Map.of()), "cortecs:glm-5");

        assertThat(sel.spec()).isEqualTo("cortecs:glm-5");
    }

    @Test
    void validate_notInAllowlist_isRefusedNamingTheSetting() {
        when(catalog.lookup("acme", "web", "cortecs", "glm-5"))
                .thenReturn(Optional.of(model("cortecs", "glm-5", Set.of(), null)));
        key("cortecs");
        tenantAllowlist("anthropic:*");

        assertThatThrownBy(() -> service.validate(process(Map.of(), Map.of()), "cortecs:glm-5"))
                .isInstanceOf(ChatModelService.ModelNotSelectableException.class)
                .hasMessageContaining("chat.allowed-models")
                .hasMessageContaining("cortecs:glm-5");
    }

    @Test
    void validate_unknownToCatalog_isRefused() {
        when(catalog.lookup(any(), any(), any(), any())).thenReturn(Optional.empty());
        key("cortecs");

        assertThatThrownBy(() -> service.validate(process(Map.of(), Map.of()), "cortecs:nope"))
                .isInstanceOf(ChatModelService.ModelNotSelectableException.class)
                .hasMessageContaining("catalog");
    }

    @Test
    void validate_instanceWithoutKey_isRefused() {
        when(catalog.lookup("acme", "web", "gemini", "gemini-pro"))
                .thenReturn(Optional.of(model("gemini", "gemini-pro", Set.of(), null)));

        assertThatThrownBy(() -> service.validate(process(Map.of(), Map.of()), "gemini:gemini-pro"))
                .isInstanceOf(ChatModelService.ModelNotSelectableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void validate_unresolvableSpec_isRefused() {
        doThrow(new AiModelResolver.UnknownModelException("no colon"))
                .when(resolver)
                .resolve(eq("nonsense"), any(), any(), any());

        assertThatThrownBy(() -> service.validate(process(Map.of(), Map.of()), "nonsense"))
                .isInstanceOf(ChatModelService.ModelNotSelectableException.class)
                .hasMessageContaining("unknown model");
    }

    @Test
    void state_reportsOverrideAndRecipeModel() {
        when(resolver.resolveOrDefault(eq("cortecs:glm-5"), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("openai", "cortecs", "glm-5"));
        when(resolver.resolveOrDefault(eq("default:chat"), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("anthropic", "anthropic", "claude-sonnet"));
        when(catalog.lookup("acme", "web", "cortecs", "glm-5"))
                .thenReturn(Optional.of(model("cortecs", "glm-5", Set.of(ModelCapability.THINKING), null)));

        ChatModelStateDto state = service.stateOf(process(
                Map.of("model", "default:chat", "thinking", "low"),
                Map.of("model", "cortecs:glm-5", "thinking", "high")));

        assertThat(state.getModel()).isEqualTo("cortecs:glm-5");
        assertThat(state.isModelOverridden()).isTrue();
        assertThat(state.getRecipeModel()).isEqualTo("anthropic:claude-sonnet");
        assertThat(state.getThinking()).isEqualTo("high");
        assertThat(state.isThinkingOverridden()).isTrue();
        assertThat(state.isModelSupportsThinking()).isTrue();
    }

    @Test
    void state_withoutOverride_reportsRecipeDefault() {
        when(resolver.resolveOrDefault(eq("default:chat"), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved("anthropic", "anthropic", "claude-sonnet"));
        when(catalog.lookup(any(), any(), any(), any())).thenReturn(Optional.empty());

        ChatModelStateDto state = service.stateOf(process(Map.of("model", "default:chat"), Map.of()));

        assertThat(state.getModel()).isEqualTo("anthropic:claude-sonnet");
        assertThat(state.isModelOverridden()).isFalse();
        assertThat(state.getThinking()).isEqualTo("off");
        assertThat(state.isThinkingOverridden()).isFalse();
        assertThat(state.isModelSupportsThinking()).isFalse();
    }
}
