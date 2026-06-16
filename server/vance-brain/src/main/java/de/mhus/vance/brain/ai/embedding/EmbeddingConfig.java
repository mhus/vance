package de.mhus.vance.brain.ai.embedding;

import org.jspecify.annotations.Nullable;

/**
 * Per-call embedding-model wiring. Mirrors the shape of
 * {@link de.mhus.vance.brain.ai.AiChatConfig} so callers can resolve
 * settings the same way for both chat and embeddings.
 *
 * <p>{@code baseUrl} is optional and routes the provider at an
 * OpenAI-compatible endpoint (Ollama, TEI, custom gateway) when set.
 * Providers that don't expose a base-URL knob ignore it.
 */
public record EmbeddingConfig(
        String provider,
        String modelName,
        String apiKey,
        @Nullable String baseUrl) {

    public EmbeddingConfig(String provider, String modelName, String apiKey) {
        this(provider, modelName, apiKey, null);
    }
}
