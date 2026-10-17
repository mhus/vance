package de.mhus.vance.brain.ai;

import de.mhus.vance.api.insights.CatalogModelInsightsDto;
import de.mhus.vance.api.insights.ModelAliasInsightsDto;
import de.mhus.vance.api.insights.ModelCatalogLayer;
import de.mhus.vance.api.insights.ModelDetailInsightsDto;
import de.mhus.vance.api.insights.ModelFieldInsightsDto;
import de.mhus.vance.api.insights.ModelInsightsDto;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The Insights "Models" tab: what the model catalog, the alias settings and
 * the chat allowlist add up to in one project. Read-only and unfiltered —
 * where the model picker ({@link ChatModelService}) shows only what may be
 * selected, this view shows everything together with the verdict, so an
 * operator can see why a model is missing from the picker.
 *
 * <p>The usability verdicts are {@link ChatModelService}'s, not a second
 * implementation: {@link ChatModelService#isInstanceConfigured} and
 * {@link ChatModelService#allowlistFor}.
 */
@Service
@RequiredArgsConstructor
public class ModelInsightsService {

    private static final String ALIAS_KEY_PREFIX = "ai.alias.";

    private static final String KIND_CHAT = "chat";

    private final ModelCatalog modelCatalog;
    private final ChatModelService chatModelService;
    private final AiModelResolver aiModelResolver;
    private final SettingService settingService;

    /** Every catalog model, the aliases and the default model as {@code projectId} sees them. */
    public ModelInsightsDto overview(String tenantId, String projectId) {
        String allowlist = chatModelService.allowlistFor(tenantId, projectId);
        Map<String, Boolean> configured = new HashMap<>();
        List<CatalogModelInsightsDto> models = new ArrayList<>();
        for (ModelCatalog.CatalogEntry entry : modelCatalog.listEntries(tenantId, projectId)) {
            models.add(toDto(entry, tenantId, projectId, allowlist, configured));
        }
        models.sort(Comparator.comparing(
                        (CatalogModelInsightsDto m) -> m.getProviderInstance().toLowerCase(Locale.ROOT))
                .thenComparing(m -> m.getModelName().toLowerCase(Locale.ROOT)));

        ModelInsightsDto.ModelInsightsDtoBuilder out = ModelInsightsDto.builder()
                .models(models)
                .aliases(aliases(tenantId, projectId))
                .chatAllowlist(allowlist);
        try {
            AiModelResolver.Resolved def = aiModelResolver.resolveOrDefault(null, tenantId, projectId, null);
            out.defaultModel(def.providerInstance() + ":" + def.modelName());
        } catch (AiModelResolver.UnknownModelException e) {
            out.defaultModelError(e.getMessage());
        }
        return out.build();
    }

    /**
     * Every field of one model and of its provider sidecar, each with its
     * source layer. {@code spec} is {@code providerInstance:modelName}; the
     * model name may itself contain colons (Ollama tags).
     */
    public Optional<ModelDetailInsightsDto> detail(String tenantId, String projectId, String spec) {
        int colon = spec.indexOf(':');
        if (colon <= 0 || colon == spec.length() - 1) {
            return Optional.empty();
        }
        String instance = spec.substring(0, colon);
        String modelName = spec.substring(colon + 1);
        Optional<ModelCatalog.CatalogEntry> entry = modelCatalog.lookupEntry(tenantId, projectId, instance, modelName);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        String allowlist = chatModelService.allowlistFor(tenantId, projectId);
        ModelCatalog.CatalogEntry e = entry.get();
        Map<String, Object> providerSpec =
                modelCatalog.lookupProvider(tenantId, projectId, e.provider()).orElse(Map.of());
        return Optional.of(ModelDetailInsightsDto.builder()
                .model(toDto(e, tenantId, projectId, allowlist, new HashMap<>()))
                .fields(fields(e.spec(), e.fieldSources()))
                .providerFields(
                        fields(providerSpec, modelCatalog.providerFieldSources(tenantId, projectId, e.provider())))
                .build());
    }

    private CatalogModelInsightsDto toDto(
            ModelCatalog.CatalogEntry entry,
            String tenantId,
            String projectId,
            String allowlist,
            Map<String, Boolean> configuredCache) {
        String spec = entry.provider() + ":" + entry.modelName();
        CatalogModelInsightsDto.CatalogModelInsightsDtoBuilder dto = CatalogModelInsightsDto.builder()
                .spec(spec)
                .providerInstance(entry.provider())
                .modelName(entry.modelName())
                .kind(entry.kind())
                .capabilities(List.of())
                .origin(origin(entry.fieldSources().values()))
                .discovered(entry.fieldSources().values().stream().anyMatch(ModelInsightsService::isAutoLayer));
        if (KIND_CHAT.equals(entry.kind())) {
            modelCatalog
                    .lookup(tenantId, projectId, entry.provider(), entry.modelName())
                    .ifPresent(info -> dto.size(info.size().name())
                            .contextWindow(info.contextWindowTokens())
                            .maxOutputTokens(info.defaultMaxOutputTokens())
                            .capabilities(info.capabilities().stream()
                                    .map(c -> c.name().toLowerCase(Locale.ROOT))
                                    .sorted()
                                    .toList())
                            .pricing(ChatModelService.pricingOf(info)));
            dto.instanceConfigured(configuredCache.computeIfAbsent(
                    entry.provider(),
                    instance ->
                            chatModelService.isInstanceConfigured(instance, entry.modelName(), tenantId, projectId)));
            dto.chatAllowed(ModelAllowlist.approved(spec, allowlist));
        }
        return dto.build();
    }

    /** Every {@code ai.alias.*} visible in the project, resolved there. */
    private List<ModelAliasInsightsDto> aliases(String tenantId, String projectId) {
        Map<String, String> settings = settingService.findByPrefixCascade(tenantId, projectId, null, ALIAS_KEY_PREFIX);
        List<ModelAliasInsightsDto> out = new ArrayList<>(settings.size());
        for (Map.Entry<String, String> setting : settings.entrySet()) {
            String rest = setting.getKey().substring(ALIAS_KEY_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                continue;
            }
            String alias = rest.substring(0, dot) + ":" + rest.substring(dot + 1);
            ModelAliasInsightsDto.ModelAliasInsightsDtoBuilder dto = ModelAliasInsightsDto.builder()
                    .alias(alias)
                    .target(setting.getValue())
                    .definedIn(definedInProject(tenantId, projectId, setting.getKey()) ? "PROJECT" : "TENANT")
                    .chain(List.of());
            try {
                AiModelResolver.Traced traced = aiModelResolver.resolveTraced(alias, tenantId, projectId);
                dto.resolved(traced.resolved().providerInstance() + ":"
                                + traced.resolved().modelName())
                        .chain(traced.chain())
                        .viaTenantDefault(traced.viaTenantDefault());
            } catch (AiModelResolver.UnknownModelException e) {
                dto.error(e.getMessage());
            }
            out.add(dto.build());
        }
        out.sort(Comparator.comparing(ModelAliasInsightsDto::getAlias));
        return out;
    }

    private boolean definedInProject(String tenantId, String projectId, String key) {
        return !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)
                && settingService.getStringValue(tenantId, SettingService.SCOPE_PROJECT, projectId, key) != null;
    }

    private static List<ModelFieldInsightsDto> fields(
            Map<String, Object> spec, Map<String, ModelCatalogLayer> sources) {
        List<ModelFieldInsightsDto> out = new ArrayList<>(spec.size());
        for (Map.Entry<String, Object> field : spec.entrySet()) {
            out.add(ModelFieldInsightsDto.builder()
                    .name(field.getKey())
                    .value(render(field.getValue()))
                    .layer(sources.getOrDefault(field.getKey(), ModelCatalogLayer.BUNDLED))
                    .build());
        }
        return out;
    }

    /** Display form of a YAML value: scalars as-is, lists in brackets, maps in braces. */
    static String render(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + render(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(ModelInsightsService::render).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(value);
    }

    /** Innermost layer among the field sources; {@code BUNDLED} for an entry without any. */
    private static ModelCatalogLayer origin(Collection<ModelCatalogLayer> layers) {
        ModelCatalogLayer out = ModelCatalogLayer.BUNDLED;
        for (ModelCatalogLayer l : layers) {
            if (l.ordinal() > out.ordinal()) {
                out = l;
            }
        }
        return out;
    }

    private static boolean isAutoLayer(ModelCatalogLayer layer) {
        return layer == ModelCatalogLayer.SYSTEM_AUTO
                || layer == ModelCatalogLayer.TENANT_AUTO
                || layer == ModelCatalogLayer.PROJECT_AUTO;
    }
}
