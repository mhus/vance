package de.mhus.vance.brain.ai;

import org.jspecify.annotations.Nullable;

/**
 * Per-call input to {@link AiModelProvider#listAvailableModels} —
 * the credentials and endpoint the provider should use to reach its
 * upstream listing API. Mirrors {@link AiChatConfig} minus
 * {@code modelName} (which is what the listing call discovers in the
 * first place).
 *
 * <p>{@code apiKey} may be blank for local providers (Ollama, LM
 * Studio) that don't authenticate. {@code baseUrl} may be {@code null}
 * to mean "use the provider's hard-wired default" (the standard
 * Anthropic / OpenAI / Gemini endpoints); a non-null value overrides
 * it for custom or self-hosted gateways.
 *
 * <p>{@code insecureTls} mirrors {@link AiChatConfig#insecureTls()}: a
 * provider sidecar declaring {@code tlsInsecure: true} routes its listing
 * call through a trust-all TLS context (private-CA gateways).
 */
public record ProviderListingRequest(
        String providerInstance, String apiKey, @Nullable String baseUrl, boolean insecureTls) {

    /** Back-compat constructor for callers predating the TLS flag.
     *  Validated TLS, as every instance had before {@code tlsInsecure} existed. */
    public ProviderListingRequest(String providerInstance, String apiKey, @Nullable String baseUrl) {
        this(providerInstance, apiKey, baseUrl, false);
    }

    public ProviderListingRequest {
        if (providerInstance == null || providerInstance.isBlank()) {
            throw new IllegalArgumentException("providerInstance is required");
        }
        if (apiKey == null) apiKey = "";
    }
}
