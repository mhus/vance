package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.insights.CatalogModelInsightsDto;
import de.mhus.vance.api.insights.ModelAliasInsightsDto;
import de.mhus.vance.api.insights.ModelAliasScope;
import de.mhus.vance.api.insights.ModelCatalogLayer;
import de.mhus.vance.api.insights.ModelDetailInsightsDto;
import de.mhus.vance.api.insights.ModelFieldInsightsDto;
import de.mhus.vance.api.insights.ModelInsightsDto;
import de.mhus.vance.shared.settings.SettingService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelInsightsServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "web";

    private ModelCatalog catalog;
    private ChatModelService chatModelService;
    private AiModelResolver resolver;
    private SettingService settings;
    private ModelInsightsService service;

    @BeforeEach
    void setUp() {
        catalog = mock(ModelCatalog.class);
        chatModelService = mock(ChatModelService.class);
        resolver = mock(AiModelResolver.class);
        settings = mock(SettingService.class);
        service = new ModelInsightsService(catalog, chatModelService, resolver, settings);
        when(chatModelService.allowlistFor(TENANT, PROJECT)).thenReturn("cortecs:*");
        when(resolver.resolveOrDefault(null, TENANT, PROJECT, null))
                .thenReturn(new AiModelResolver.Resolved("openai", "cortecs", "glm-4.6"));
    }

    private static ModelCatalog.CatalogEntry entry(
            String instance, String name, String kind, Map<String, ModelCatalogLayer> sources) {
        Map<String, Object> spec = new LinkedHashMap<>();
        sources.keySet().forEach(k -> spec.put(k, "v-" + k));
        return new ModelCatalog.CatalogEntry(instance, name, kind, spec, sources);
    }

    private static ModelInfo chatInfo(String instance, String name) {
        return new ModelInfo(
                instance,
                name,
                128000,
                8192,
                ModelSize.SMALL,
                Set.of(ModelCapability.THINKING, ModelCapability.VISION),
                60,
                2,
                false,
                null,
                new ModelInfo.Pricing("USD", 0.5, 1.5, null, null),
                OutputTokenParam.MAX_TOKENS,
                Set.of(),
                null);
    }

    @Test
    void overview_listsEveryKind_withVerdictsForChatModelsOnly() {
        when(catalog.listEntries(TENANT, PROJECT))
                .thenReturn(List.of(
                        entry("openai", "gpt-5", "chat", Map.of("size", ModelCatalogLayer.BUNDLED)),
                        entry(
                                "cortecs",
                                "glm-4.6",
                                "chat",
                                Map.of(
                                        "size", ModelCatalogLayer.BUNDLED,
                                        "contextWindowTokens", ModelCatalogLayer.PROJECT_AUTO)),
                        entry("cortecs", "flux", "image", Map.of("kind", ModelCatalogLayer.TENANT))));
        when(catalog.lookup(TENANT, PROJECT, "cortecs", "glm-4.6"))
                .thenReturn(Optional.of(chatInfo("cortecs", "glm-4.6")));
        when(catalog.lookup(TENANT, PROJECT, "openai", "gpt-5")).thenReturn(Optional.of(chatInfo("openai", "gpt-5")));
        when(chatModelService.isInstanceConfigured(eq("cortecs"), any(), eq(TENANT), eq(PROJECT)))
                .thenReturn(true);
        when(chatModelService.isInstanceConfigured(eq("openai"), any(), eq(TENANT), eq(PROJECT)))
                .thenReturn(false);

        ModelInsightsDto out = service.overview(TENANT, PROJECT);

        assertThat(out.getModels())
                .extracting(CatalogModelInsightsDto::getSpec)
                .containsExactly("cortecs:flux", "cortecs:glm-4.6", "openai:gpt-5");
        CatalogModelInsightsDto glm = out.getModels().get(1);
        assertThat(glm.getSize()).isEqualTo("SMALL");
        assertThat(glm.getCapabilities()).containsExactly("thinking", "vision");
        assertThat(glm.getPricing().getInputPerMTok()).isEqualTo(0.5);
        assertThat(glm.getOrigin()).isEqualTo(ModelCatalogLayer.PROJECT_AUTO);
        assertThat(glm.isDiscovered()).isTrue();
        assertThat(glm.getInstanceConfigured()).isTrue();
        assertThat(glm.getChatAllowed()).isTrue();

        CatalogModelInsightsDto gpt = out.getModels().get(2);
        assertThat(gpt.getInstanceConfigured()).isFalse();
        assertThat(gpt.getChatAllowed()).isFalse();
        assertThat(gpt.isDiscovered()).isFalse();

        CatalogModelInsightsDto flux = out.getModels().get(0);
        assertThat(flux.getKind()).isEqualTo("image");
        assertThat(flux.getOrigin()).isEqualTo(ModelCatalogLayer.TENANT);
        assertThat(flux.getInstanceConfigured()).isNull();
        assertThat(flux.getChatAllowed()).isNull();
        assertThat(flux.getSize()).isNull();

        assertThat(out.getChatAllowlist()).isEqualTo("cortecs:*");
        assertThat(out.getDefaultModel()).isEqualTo("cortecs:glm-4.6");
    }

    @Test
    void overview_resolvesAliases_andNamesTheirSettingsLayer() {
        when(catalog.listEntries(TENANT, PROJECT)).thenReturn(List.of());
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("ai.alias.default.fast", "cortecs:glm-4.6");
        aliases.put("ai.alias.default.chat", "default:fast");
        aliases.put("ai.alias.cheap.broken", "nowhere:x");
        when(settings.findByPrefixCascade(TENANT, PROJECT, null, "ai.alias.")).thenReturn(aliases);
        when(settings.getStringValue(TENANT, SettingService.SCOPE_PROJECT, PROJECT, "ai.alias.default.chat"))
                .thenReturn("default:fast");
        AiModelResolver.Resolved glm = new AiModelResolver.Resolved("openai", "cortecs", "glm-4.6");
        when(resolver.resolveTraced("default:fast", TENANT, PROJECT))
                .thenReturn(new AiModelResolver.Traced(glm, List.of("default:fast"), false));
        when(resolver.resolveTraced("default:chat", TENANT, PROJECT))
                .thenReturn(new AiModelResolver.Traced(glm, List.of("default:chat", "default:fast"), false));
        when(resolver.resolveTraced("cheap:broken", TENANT, PROJECT))
                .thenThrow(new AiModelResolver.UnknownModelException("Unknown model spec 'nowhere:x'"));

        List<ModelAliasInsightsDto> out = service.overview(TENANT, PROJECT).getAliases();

        assertThat(out)
                .extracting(ModelAliasInsightsDto::getAlias)
                .containsExactly("cheap:broken", "default:chat", "default:fast");
        assertThat(out.get(0).getResolved()).isNull();
        assertThat(out.get(0).getError()).contains("nowhere:x");
        assertThat(out.get(1).getDefinedIn()).isEqualTo(ModelAliasScope.PROJECT);
        assertThat(out.get(1).getChain()).containsExactly("default:chat", "default:fast");
        assertThat(out.get(1).getResolved()).isEqualTo("cortecs:glm-4.6");
        assertThat(out.get(2).getDefinedIn()).isEqualTo(ModelAliasScope.TENANT);
    }

    @Test
    void overview_reportsABrokenDefault() {
        when(catalog.listEntries(TENANT, PROJECT)).thenReturn(List.of());
        when(resolver.resolveOrDefault(null, TENANT, PROJECT, null))
                .thenThrow(new AiModelResolver.UnknownModelException("tenant 'acme' has no 'ai.default.provider'"));

        ModelInsightsDto out = service.overview(TENANT, PROJECT);

        assertThat(out.getDefaultModel()).isNull();
        assertThat(out.getDefaultModelError()).contains("ai.default.provider");
    }

    @Test
    void detail_splitsTheSpecAtTheFirstColon_andCarriesFieldLayers() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("wireName", "qwen3:30b");
        spec.put("capabilities", List.of("thinking", "tools"));
        spec.put("pricing", Map.of("currency", "USD"));
        when(catalog.lookupEntry(TENANT, PROJECT, "ollama", "qwen3:30b"))
                .thenReturn(Optional.of(new ModelCatalog.CatalogEntry(
                        "ollama",
                        "qwen3:30b",
                        "chat",
                        spec,
                        Map.of(
                                "wireName", ModelCatalogLayer.BUNDLED,
                                "capabilities", ModelCatalogLayer.PROJECT,
                                "pricing", ModelCatalogLayer.TENANT))));
        when(catalog.lookupProvider(TENANT, PROJECT, "ollama")).thenReturn(Optional.of(Map.of("wireType", "ollama")));
        when(catalog.providerFieldSources(TENANT, PROJECT, "ollama"))
                .thenReturn(Map.of("wireType", ModelCatalogLayer.BUNDLED));

        ModelDetailInsightsDto out =
                service.detail(TENANT, PROJECT, "ollama:qwen3:30b").orElseThrow();

        assertThat(out.getModel().getSpec()).isEqualTo("ollama:qwen3:30b");
        assertThat(out.getFields())
                .extracting(
                        ModelFieldInsightsDto::getName,
                        ModelFieldInsightsDto::getValue,
                        ModelFieldInsightsDto::getLayer)
                .containsExactly(
                        tuple("wireName", "qwen3:30b", ModelCatalogLayer.BUNDLED),
                        tuple("capabilities", "[thinking, tools]", ModelCatalogLayer.PROJECT),
                        tuple("pricing", "{currency: USD}", ModelCatalogLayer.TENANT));
        assertThat(out.getProviderFields())
                .extracting(ModelFieldInsightsDto::getName)
                .containsExactly("wireType");
    }

    @Test
    void detail_unknownOrMalformedSpec_isEmpty() {
        when(catalog.lookupEntry(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.detail(TENANT, PROJECT, "cortecs:ghost")).isEmpty();
        assertThat(service.detail(TENANT, PROJECT, "no-colon")).isEmpty();
        assertThat(service.detail(TENANT, PROJECT, "trailing:")).isEmpty();
    }
}
