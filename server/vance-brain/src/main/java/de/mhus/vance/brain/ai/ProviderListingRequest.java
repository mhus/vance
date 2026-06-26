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
 */
public record ProviderListingRequest(
        String providerInstance,
        String apiKey,
        @Nullable String baseUrl) {

    public ProviderListingRequest {
        if (providerInstance == null || providerInstance.isBlank()) {
            throw new IllegalArgumentException("providerInstance is required");
        }
        if (apiKey == null) apiKey = "";
    }
}
