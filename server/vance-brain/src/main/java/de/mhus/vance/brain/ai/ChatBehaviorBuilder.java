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
 *
 * <p>Which settings layers all of that is read from is decided by
 * {@code params.aiScope} — see {@link AiConfigScope}. Default is the full
 * project cascade; {@code tenant} pins model <em>and</em> endpoint to the
 * {@code _tenant} layer.
 */
public final class ChatBehaviorBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatBehaviorBuilder.class);
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";
    /**
     * Per-tenant/-project override for the provider-instance base URL.
     * Lets a tenant route through cortecs.ai, OpenRouter, vLLM, or any other
     * OpenAI-wire gateway without touching {@code application.yml}. Empty /
     * missing → Spring boot-time default ({@code vance.ai.<provider>.base-url}).
     *
     * <p>Keyed on the <em>instance</em> name, not the protocol — so two named
     * instances of the same protocol (e.g. real OpenAI + a deepseek-direct
     * instance) read their own base URL each.
     */
    private static final String SETTING_PROVIDER_BASE_URL_FMT = "ai.provider.%s.baseUrl";

    private ChatBehaviorBuilder() {}

    /**
     * Reads the optional per-tenant base-URL override for the given provider
     * <em>instance</em> via the project cascade ({@code process → project →
     * _tenant}). Returns {@code null} when nothing is configured — the provider
     * then keeps its Spring boot-time default.
     */
    public static @Nullable String resolveBaseUrl(
            String providerInstance,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            SettingService settings) {
        String key = String.format(SETTING_PROVIDER_BASE_URL_FMT, providerInstance);
        String value = settings.getStringValueCascade(tenantId, projectId, processId, key);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

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
        // A tenant-pinned recipe resolves its whole endpoint (alias,
        // default, apiKey, baseUrl) from the _tenant layer: passing null
        // for both inner scopes collapses the cascade to its base layer,
        // so model and endpoint cannot come from different layers.
        AiConfigScope aiScope = readAiConfigScope(process);
        boolean pinned = aiScope == AiConfigScope.TENANT;
        @Nullable String processId = pinned ? null : process.getId();
        // projectId is denormalised onto ThinkProcessDocument at spawn
        // time — empty string means "unknown / system-wide", which the
        // cascade collapses to the _tenant layer only.
        @Nullable String projectId = pinned ? null : process.getProjectId();
        if (pinned) {
            log.debug("ChatBehaviorBuilder: process {} pins AI config to the tenant layer "
                    + "(project '{}' ignored)", process.getId(), process.getProjectId());
        }
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
     * ({@code process → project → _tenant}).
     */
    public static AiChatConfig resolveOne(
            @Nullable String spec,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            SettingService settings,
            AiModelResolver resolver) {
        AiModelResolver.Resolved resolved = resolver.resolveOrDefault(spec, tenantId, projectId, processId);
        String apiKey = resolveApiKey(
                resolved.provider(), resolved.providerInstance(),
                tenantId, projectId, processId, settings);
        String baseUrl = resolveBaseUrl(
                resolved.providerInstance(), tenantId, projectId, processId, settings);
        return new AiChatConfig(
                resolved.provider(), resolved.providerInstance(),
                resolved.modelName(), apiKey, baseUrl);
    }

    /**
     * Reads the API-key for the given provider instance via the project
     * cascade, decrypts it, and returns the plaintext. For keyless providers
     * (Ollama, LM Studio — see {@link ProviderType#requiresApiKey()})
     * the setting lookup is skipped and a placeholder is returned —
     * the providers don't read the field, but {@link AiChatConfig}'s
     * record contract still requires a non-blank value.
     *
     * <p>{@code providerType} drives the {@code requiresApiKey} check;
     * {@code providerInstance} is the lookup label for the setting path
     * {@code ai.provider.<instance>.apiKey} — different named instances of
     * the same protocol read different keys.
     *
     * @throws IllegalStateException when the provider does require a
     *         key and none is set in any cascade layer.
     */
    public static String resolveApiKey(
            String providerType,
            String providerInstance,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            SettingService settings) {
        ProviderType type = ProviderType.fromWireName(providerType).orElse(null);
        if (type != null && !type.requiresApiKey()) {
            return KEYLESS_PLACEHOLDER;
        }
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, providerInstance);
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, projectId, processId, apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider instance '" + providerInstance
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return apiKey;
    }

    /**
     * Placeholder string handed to {@link AiChatConfig} for providers
     * that don't authenticate. Visible only inside the record; the
     * adapters never pass it to the wire.
     */
    public static final String KEYLESS_PLACEHOLDER = "no-key-required";

    /**
     * Convenience wrapper that builds a single primary {@link AiChatConfig}
     * straight from a process — handy for engines that don't need the
     * fallback chain ({@link ChatBehavior}) but only one config (judge
     * calls, side-channel summaries, compaction LLM).
     *
     * <p>Combines the model-spec parsing
     * ({@link #readModelSpec(ThinkProcessDocument)}) with the per-config
     * {@link #resolveOne} pipeline (alias → API-key + base-URL cascade
     * via settings). Engines should prefer this over inlining the
     * provider-key / base-URL / model-spec lookup themselves.
     *
     * <p>Honours {@code params.aiScope} exactly like
     * {@link #fromProcess} — a tenant-pinned process must not leak the
     * project layer in through the single-config path.
     */
    public static AiChatConfig resolveForProcess(
            ThinkProcessDocument process,
            SettingService settings,
            AiModelResolver resolver) {
        String spec = readModelSpec(process);
        boolean pinned = readAiConfigScope(process) == AiConfigScope.TENANT;
        return resolveOne(spec,
                process.getTenantId(),
                pinned ? null : process.getProjectId(),
                pinned ? null : process.getId(),
                settings,
                resolver);
    }

    /**
     * Model-spec parsing each engine uses. Delegates to the shared
     * {@link AiModelResolver#parseModelSpec(Map)} so process-driven
     * resolution and {@code LightLlmService}-driven resolution share
     * one parser.
     */
    public static @Nullable String readModelSpec(ThinkProcessDocument process) {
        return AiModelResolver.parseModelSpec(process.getEngineParams());
    }

    /**
     * Resolve the effective {@link AiConfigScope} for this process from
     * {@code params.aiScope}, read through
     * {@link EngineChatFactory#effectiveParams} so a runtime override is
     * honoured the same way {@code params.thinking} /
     * {@code params.temperature} are.
     *
     * <p>Unknown or wrongly-typed values fall back to
     * {@link AiConfigScope#CASCADE} with a warning rather than failing the
     * turn — a typo in a recipe must not take the engine down, and the
     * cascade is the behaviour every engine had before the param existed.
     */
    public static AiConfigScope readAiConfigScope(ThinkProcessDocument process) {
        Object v = EngineChatFactory.effectiveParams(process).get(AiConfigScope.PARAM_KEY);
        if (v == null) {
            return AiConfigScope.CASCADE;
        }
        if (v instanceof String s) {
            return AiConfigScope.fromString(s).orElseGet(() -> {
                log.warn("Unknown params.aiScope='{}' on process '{}' — falling back to {}",
                        s, process.getId(), AiConfigScope.CASCADE);
                return AiConfigScope.CASCADE;
            });
        }
        log.warn("params.aiScope on process '{}' has unexpected type {} — ignoring",
                process.getId(), v.getClass().getSimpleName());
        return AiConfigScope.CASCADE;
    }

    /**
     * Fallback aliases from {@code params.fallbackModels}. Public because
     * the tool-surface budget has to know the <em>whole</em> chain: the
     * manifest is built once per turn but the resilient layer may advance
     * to a fallback afterwards, so the cap is the minimum over all
     * entries (see {@code ToolBudgetService}).
     */
    @SuppressWarnings("unchecked")
    public static List<String> readFallbackAliases(ThinkProcessDocument process) {
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
}
