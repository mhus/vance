package de.mhus.vance.brain.ai;

import de.mhus.vance.api.chatmodel.ChatModelDto;
import de.mhus.vance.api.chatmodel.ChatModelListDto;
import de.mhus.vance.api.chatmodel.ChatModelPricingDto;
import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The model picker's single authority: which chat models a user may select
 * for a chat process, whether a given choice is acceptable, and which model
 * / reasoning level a process currently runs with.
 *
 * <p><b>Selectable</b> means three things at once: the model is a chat model
 * in the {@link ModelCatalog} view of the scope, its provider instance is
 * configured there (an API key, or a base URL for keyless instances), and
 * it matches the {@value #ALLOWED_MODELS_KEY} setting.
 *
 * <p><b>Allowlist policy.</b> Relaxed like Trillian's: unset on every layer
 * means {@code *}. Read over {@code project → _tenant} <em>without</em> the
 * think-process layer, so a process-scoped {@code setting_set} cannot widen
 * its own gate. A blank value at a layer is skipped, not a cascade stop —
 * lifting a tenant restriction for one project takes an explicit {@code *}.
 * The allowlist restricts the user's choice only; recipes that pin a model
 * outside it keep running.
 */
@Service
@RequiredArgsConstructor
public class ChatModelService {

    public static final String ALLOWED_MODELS_KEY = "chat.allowed-models";

    /** Unset means "every model allowed" — see class javadoc. */
    public static final String DEFAULT_ALLOWLIST = "*";

    private static final String SETTING_PROVIDER_BASE_URL_FMT = "ai.provider.%s.baseUrl";

    private final ModelCatalog modelCatalog;
    private final AiModelResolver aiModelResolver;
    private final SettingService settingService;

    /** Thrown by {@link #validate} when a model may not be selected; the message names the reason. */
    public static class ModelNotSelectableException extends RuntimeException {
        public ModelNotSelectableException(String message) {
            super(message);
        }

        public ModelNotSelectableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A validated choice: the resolved spec plus its catalog entry. */
    public record Selection(String spec, ModelInfo info) {}

    /** The effective allowlist for a project: first non-blank of project, {@code _tenant} — else {@code *}. */
    public String allowlistFor(String tenantId, @Nullable String projectId) {
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String v = settingService.getStringValue(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, ALLOWED_MODELS_KEY);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        String v = settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, ALLOWED_MODELS_KEY);
        return v != null && !v.isBlank() ? v.trim() : DEFAULT_ALLOWLIST;
    }

    /** All chat models selectable in {@code projectId}, sorted by provider instance, then model name. */
    public ChatModelListDto listSelectable(String tenantId, @Nullable String projectId) {
        String allowlist = allowlistFor(tenantId, projectId);
        Map<String, Boolean> configured = new HashMap<>();
        List<ModelInfo> models = new ArrayList<>();
        for (ModelInfo info : modelCatalog.listAll(tenantId, projectId)) {
            String spec = specOf(info);
            if (!ModelAllowlist.approved(spec, allowlist)) {
                continue;
            }
            boolean ok = configured.computeIfAbsent(
                    info.provider(), instance -> isInstanceConfigured(instance, info.modelName(), tenantId, projectId));
            if (ok) {
                models.add(info);
            }
        }
        models.sort(Comparator.comparing((ModelInfo m) -> m.provider().toLowerCase(Locale.ROOT))
                .thenComparing(m -> m.modelName().toLowerCase(Locale.ROOT)));
        return ChatModelListDto.builder()
                .models(models.stream().map(ChatModelService::toDto).toList())
                .thinkingLevels(thinkingLevels())
                .build();
    }

    /**
     * Checks that {@code spec} may be selected for {@code process} and
     * resolves it to {@code providerInstance:modelName}. Aliases are
     * accepted; the check runs on what they resolve to.
     *
     * @throws ModelNotSelectableException naming why the model is refused
     */
    public Selection validate(ThinkProcessDocument process, String spec) {
        ScopeView scope = scopeFor(process);
        AiModelResolver.Resolved resolved;
        try {
            resolved = aiModelResolver.resolve(spec, process.getTenantId(), scope.projectId(), scope.processId());
        } catch (AiModelResolver.UnknownModelException e) {
            throw new ModelNotSelectableException("unknown model '" + spec + "': " + e.getMessage(), e);
        }
        String resolvedSpec = resolved.providerInstance() + ":" + resolved.modelName();
        Optional<ModelInfo> info = modelCatalog.lookup(
                process.getTenantId(), scope.projectId(), resolved.providerInstance(), resolved.modelName());
        if (info.isEmpty()) {
            throw new ModelNotSelectableException(
                    "model '" + resolvedSpec + "' is not a chat model in the model catalog of this project");
        }
        if (!isInstanceConfigured(
                resolved.providerInstance(), resolved.modelName(), process.getTenantId(), scope.projectId())) {
            throw new ModelNotSelectableException(
                    "provider instance '" + resolved.providerInstance() + "' is not configured in this project");
        }
        String allowlist = allowlistFor(process.getTenantId(), process.getProjectId());
        if (!ModelAllowlist.approved(resolvedSpec, allowlist)) {
            throw new ModelNotSelectableException("model '" + resolvedSpec + "' is not approved by setting '"
                    + ALLOWED_MODELS_KEY + "' (current allowlist: '" + allowlist + "')");
        }
        return new Selection(resolvedSpec, info.get());
    }

    /** Model and reasoning level {@code process} runs with on its next model call. */
    public ChatModelStateDto stateOf(ThinkProcessDocument process) {
        ScopeView scope = scopeFor(process);
        AiModelResolver.Resolved effective = aiModelResolver.resolveOrDefault(
                ChatBehaviorBuilder.readModelSpec(process),
                process.getTenantId(),
                scope.projectId(),
                scope.processId());
        String recipeModel;
        try {
            AiModelResolver.Resolved recipe = aiModelResolver.resolveOrDefault(
                    ChatBehaviorBuilder.readRecipeModelSpec(process),
                    process.getTenantId(),
                    scope.projectId(),
                    scope.processId());
            recipeModel = recipe.providerInstance() + ":" + recipe.modelName();
        } catch (RuntimeException e) {
            recipeModel = null;
        }
        Map<String, Object> overrides = process.getEngineParamOverrides();
        boolean thinkingOverridden = overrides != null && overrides.containsKey("thinking");
        boolean supportsThinking = modelCatalog
                .lookup(process.getTenantId(), scope.projectId(), effective.providerInstance(), effective.modelName())
                .map(i -> i.supports(ModelCapability.THINKING))
                .orElse(false);
        return ChatModelStateDto.builder()
                .model(effective.providerInstance() + ":" + effective.modelName())
                .modelOverridden(ChatBehaviorBuilder.hasModelOverride(process))
                .recipeModel(recipeModel)
                .thinking(EngineChatFactory.readThinkingLevel(process).name().toLowerCase(Locale.ROOT))
                .thinkingOverridden(thinkingOverridden)
                .modelSupportsThinking(supportsThinking)
                .build();
    }

    /** {@code //thinking} vocabulary, lowest first. */
    public static List<String> thinkingLevels() {
        List<String> out = new ArrayList<>();
        for (ThinkingLevel level : ThinkingLevel.values()) {
            out.add(level.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * A provider instance counts as configured when the chat itself could be
     * built for it: it resolves and has an API key — or, for a keyless
     * protocol (Ollama, LM Studio), an explicit base URL, since a keyless
     * instance would otherwise always look available.
     */
    private boolean isInstanceConfigured(
            String instance, String modelName, String tenantId, @Nullable String projectId) {
        AiModelResolver.Resolved resolved;
        try {
            resolved = aiModelResolver.resolve(instance + ":" + modelName, tenantId, projectId, null);
        } catch (AiModelResolver.UnknownModelException e) {
            return false;
        }
        boolean keyless = ProviderType.fromWireName(resolved.provider())
                .map(t -> !t.requiresApiKey())
                .orElse(false);
        if (keyless) {
            String baseUrl = settingService.getStringValueCascade(
                    tenantId, projectId, null, String.format(SETTING_PROVIDER_BASE_URL_FMT, instance));
            return baseUrl != null && !baseUrl.isBlank();
        }
        try {
            ChatBehaviorBuilder.resolveApiKey(resolved.provider(), instance, tenantId, projectId, null, settingService);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static String specOf(ModelInfo info) {
        return info.provider() + ":" + info.modelName();
    }

    private static ChatModelDto toDto(ModelInfo info) {
        ModelInfo.Pricing p = info.pricing();
        return ChatModelDto.builder()
                .spec(specOf(info))
                .providerInstance(info.provider())
                .modelName(info.modelName())
                .size(info.size().name())
                .thinking(info.supports(ModelCapability.THINKING))
                .contextWindow(info.contextWindowTokens())
                .pricing(
                        p == null
                                ? null
                                : ChatModelPricingDto.builder()
                                        .currency(p.currency())
                                        .inputPerMTok(p.inputPerMTok())
                                        .outputPerMTok(p.outputPerMTok())
                                        .build())
                .build();
    }

    /** Settings view the process's chat is built from — {@code params.aiScope} pinning included. */
    private static ScopeView scopeFor(ThinkProcessDocument process) {
        boolean pinned = ChatBehaviorBuilder.readAiConfigScope(process) == AiConfigScope.TENANT;
        return pinned ? new ScopeView(null, null) : new ScopeView(process.getProjectId(), process.getId());
    }

    private record ScopeView(
            @Nullable String projectId, @Nullable String processId) {}
}
