package de.mhus.vance.brain.ai;

import org.jspecify.annotations.Nullable;

/**
 * Resolved configuration for an {@link AiChat}: which protocol, which named
 * instance under that protocol, which model, and the credential to authenticate
 * with. All lookups (which model is "default" for a scope, where the API key
 * lives as a setting) happen <b>before</b> this record is built —
 * {@link AiModelService} takes the record as-is and instantiates the chat.
 *
 * @param provider         protocol wire-name registered in
 *                         {@link AiModelService} (e.g. {@code "anthropic"},
 *                         {@code "openai"}). Drives adapter dispatch.
 * @param providerInstance instance label used for the
 *                         {@code ai.provider.<instance>.apiKey} /
 *                         {@code .baseUrl} setting lookup and as the
 *                         {@link ModelCatalog} indexing key. Equals
 *                         {@code provider} for the default instance; differs
 *                         only when the spec referenced a named provider
 *                         instance configured via
 *                         {@code ai.provider.<instance>.type}.
 * @param modelName        provider-specific model identifier (e.g.
 *                         {@code "claude-sonnet-4-5"})
 * @param apiKey           provider credential (plaintext — the caller already
 *                         decrypted it via {@code SettingService.getDecryptedPassword})
 * @param baseUrl          optional per-tenant/-project base-URL override (e.g. the
 *                         {@code cortecs.ai} gateway URL on top of the OpenAI
 *                         adapter). {@code null} means "use the provider's
 *                         boot-time default" — the Spring {@code @Value} fallback.
 *                         Resolved from setting key
 *                         {@code ai.provider.<instance>.baseUrl} via the normal
 *                         project cascade.
 */
public record AiChatConfig(
        String provider,
        String providerInstance,
        String modelName,
        String apiKey,
        @Nullable String baseUrl) {

    /** Back-compat convenience: instance defaults to the protocol wire-name. */
    public AiChatConfig(String provider, String modelName, String apiKey, @Nullable String baseUrl) {
        this(provider, provider, modelName, apiKey, baseUrl);
    }

    /** Back-compat convenience for callers that don't override the base URL. */
    public AiChatConfig(String provider, String modelName, String apiKey) {
        this(provider, provider, modelName, apiKey, null);
    }

    public AiChatConfig {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is blank");
        }
        if (providerInstance == null || providerInstance.isBlank()) {
            throw new IllegalArgumentException("providerInstance is blank");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is blank");
        }
        // baseUrl normalisation: empty string → null (so the provider treats
        // "unset" and "set to empty" the same way and falls back to its default).
        if (baseUrl != null && baseUrl.isBlank()) {
            baseUrl = null;
        }
    }

    /**
     * {@code "instance:modelName"} form — what the user wrote in the model
     * spec. Used for logs and the AiChat name. Equals {@code provider:modelName}
     * for the default instance.
     */
    public String fullName() {
        return providerInstance + ":" + modelName;
    }

    /**
     * Typed view of {@link #provider()}. Validates the wire-name against the
     * known {@link ProviderType} set — a recipe / setting with an unknown
     * provider blows up at config-build time, not at dispatch time.
     */
    public ProviderType providerType() {
        return ProviderType.requireWireName(provider);
    }
}
