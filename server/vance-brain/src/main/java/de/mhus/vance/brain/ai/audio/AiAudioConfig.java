package de.mhus.vance.brain.ai.audio;

import de.mhus.vance.brain.ai.ProviderType;
import org.jspecify.annotations.Nullable;

/**
 * Resolved configuration for one audio call. Mirrors
 * {@code AiImageConfig}: all lookups (which model is {@code
 * default:tts} for the scope, where the API key lives) happen before
 * this record is built — {@link AiAudioService} takes it as-is and
 * dispatches to the matching provider.
 *
 * @param provider         protocol wire-name registered as a
 *                         {@link ProviderType} (e.g. {@code "openai"},
 *                         {@code "local"})
 * @param providerInstance instance label used for the
 *                         {@code ai.provider.<instance>.apiKey} /
 *                         {@code .baseUrl} setting lookup. Equals
 *                         {@code provider} for the default instance.
 * @param modelName        provider-specific model identifier
 *                         (e.g. {@code "gemini-3.8-flash-lite-tts"})
 * @param apiKey           plaintext provider credential ({@code null}
 *                         for adapters that need no credential — the
 *                         local whisper adapter)
 * @param baseUrl          optional per-tenant/-project base-URL
 *                         override; {@code null} = provider default
 * @param timeoutSeconds   per-call HTTP timeout the provider must
 *                         apply to its HTTP client
 */
public record AiAudioConfig(
        String provider,
        String providerInstance,
        String modelName,
        @Nullable String apiKey,
        @Nullable String baseUrl,
        int timeoutSeconds) {

    public AiAudioConfig {
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
    }

    /** {@code "instance:modelName"} form — what the caller resolved. */
    public String fullName() {
        return providerInstance + ":" + modelName;
    }

    /** Typed view of {@link #provider()}. */
    public ProviderType providerType() {
        return ProviderType.requireWireName(provider);
    }
}
