package de.mhus.vance.brain.ai.decision;

import de.mhus.vance.brain.ai.ProviderType;
import org.jspecify.annotations.Nullable;

/**
 * Resolved configuration for one decision call. Same role as
 * {@code AiAudioConfig}: alias resolution, key decryption and the
 * catalog lookup happen before this record is built; the providers
 * take it as-is.
 *
 * @param provider         protocol wire-name of the instance
 *                         ({@code openai} for OpenRouter, {@code ollama},
 *                         {@code systemone})
 * @param providerInstance instance label ({@code openrouter},
 *                         {@code ollama}, …) — first dispatch key
 * @param modelName        provider-specific model id
 * @param apiKey           plaintext credential, {@code null} for
 *                         key-less instances (local Ollama)
 * @param baseUrl          instance base-URL override, {@code null} =
 *                         provider default
 * @param timeoutSeconds   per-call HTTP timeout
 */
public record AiDecisionConfig(
        String provider,
        String providerInstance,
        String modelName,
        @Nullable String apiKey,
        @Nullable String baseUrl,
        int timeoutSeconds) {

    public AiDecisionConfig {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is blank");
        }
        if (providerInstance == null || providerInstance.isBlank()) {
            throw new IllegalArgumentException("providerInstance is blank");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is blank");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0, got " + timeoutSeconds);
        }
        if (baseUrl != null && baseUrl.isBlank()) {
            baseUrl = null;
        }
        if (apiKey != null && apiKey.isBlank()) {
            apiKey = null;
        }
    }

    /** {@code "instance:modelName"} — what the caller resolved. */
    public String fullName() {
        return providerInstance + ":" + modelName;
    }

    /** Typed view of {@link #provider()}. */
    public ProviderType providerType() {
        return ProviderType.requireWireName(provider);
    }

    /** Never prints the key — a config ends up in log lines and exceptions. */
    @Override
    public String toString() {
        return "AiDecisionConfig[" + fullName() + ", protocol=" + provider + ", baseUrl=" + baseUrl + ", key="
                + (apiKey == null ? "none" : "***") + ", timeout=" + timeoutSeconds + "s]";
    }
}
