package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link ChatBehavior} (primary + ordered fallbacks) from a
 * process's {@code engineParams}. Centralised here so every engine can
 * opt in with one call instead of re-implementing alias resolution +
 * API-key lookup + fallback iteration.
 *
 * <p>Reads:
 * <ul>
 *   <li>{@code params.model} — primary model alias / spec
 *       (e.g. {@code "default:analyze"} or {@code "anthropic:claude-…"})</li>
 *   <li>{@code params.provider} — legacy companion to {@code params.model}
 *       (kept for backward-compat with non-aliased recipes)</li>
 *   <li>{@code params.fallbackModels} — optional {@code List<String>} of
 *       alias / spec strings tried in order after the primary's retry
 *       budget is exhausted. Empty / missing → single-entry behaviour</li>
 * </ul>
 *
 * <p>API keys come from {@link SettingService} via the
 * {@code ai.provider.<name>.apiKey} setting at tenant scope. If a
 * fallback's provider has no key configured, that entry is dropped from
 * the chain with a warning — the chain still works as long as at least
 * one entry is reachable.
 */
public final class ChatBehaviorBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatBehaviorBuilder.class);
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private ChatBehaviorBuilder() {}

    /**
     * Build a {@link ChatBehavior} for {@code process}. Always returns a
     * non-null behaviour with at least one entry (the primary); throws
     * {@link IllegalStateException} if the primary itself can't be
     * resolved (no API key etc.).
     */
    public static ChatBehavior fromProcess(
            ThinkProcessDocument process,
            SettingService settings,
            AiModelResolver resolver) {
        String tenantId = process.getTenantId();
        String processId = process.getId();
        // projectId is not denormalised onto ThinkProcessDocument — the
        // project cascade falls through to _vance only. Callers that
        // want project-level overrides need to look up the session and
        // reach this builder via a richer entry point.
        @Nullable String projectId = null;
        List<ChatBehavior.Entry> entries = new ArrayList<>();

        // Primary
        String primarySpec = readModelSpec(process);
        AiChatConfig primary = resolveOne(primarySpec, tenantId, projectId, processId, settings, resolver);
        entries.add(new ChatBehavior.Entry(primary, "primary"));

        // Fallbacks
        List<String> fallbackAliases = readFallbackAliases(process);
        for (String alias : fallbackAliases) {
            try {
                AiChatConfig fbConfig = resolveOne(alias, tenantId, projectId, processId, settings, resolver);
                entries.add(new ChatBehavior.Entry(fbConfig, "fallback:" + alias));
            } catch (RuntimeException e) {
                log.warn("ChatBehaviorBuilder: dropping unreachable fallback '{}' "
                        + "for tenant '{}': {}", alias, tenantId, e.getMessage());
            }
        }
        if (entries.size() > 1) {
            log.debug("ChatBehavior for process {}: primary {} + {} fallback(s)",
                    process.getId(), primary.modelName(), entries.size() - 1);
        }
        return new ChatBehavior(entries);
    }

    /**
     * Resolve a single alias / spec into an {@link AiChatConfig}, including
     * the matching API key. Reads through the project cascade
     * ({@code process → project → _vance}).
     */
    public static AiChatConfig resolveOne(
            @Nullable String spec,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            SettingService settings,
            AiModelResolver resolver) {
        AiModelResolver.Resolved resolved = resolver.resolveOrDefault(spec, tenantId, projectId, processId);
        String apiKeySetting = String.format(
                SETTING_PROVIDER_API_KEY_FMT, resolved.provider());
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, projectId, processId, apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + resolved.provider()
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(resolved.provider(), resolved.modelName(), apiKey);
    }

    /**
     * Recreates the model-spec parsing each engine does today: prefers
     * {@code params.model} (with its own colon-and-provider quirks), falls
     * back to alias-default-namespace, otherwise null → tenant default.
     */
    private static @Nullable String readModelSpec(ThinkProcessDocument process) {
        String paramModel = paramString(process, "model");
        String paramProvider = paramString(process, "provider");
        if (paramModel != null && paramModel.contains(":")) {
            return paramModel;
        }
        if (paramModel != null && paramProvider != null) {
            return paramProvider + ":" + paramModel;
        }
        if (paramModel != null) {
            return "default:" + paramModel;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readFallbackAliases(ThinkProcessDocument process) {
        Object raw = param(process, "fallbackModels");
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out;
        }
        return List.of();
    }

    private static @Nullable Object param(ThinkProcessDocument process, String key) {
        Map<String, Object> params = process.getEngineParams();
        return params == null ? null : params.get(key);
    }

    private static @Nullable String paramString(ThinkProcessDocument process, String key) {
        Object v = param(process, key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
