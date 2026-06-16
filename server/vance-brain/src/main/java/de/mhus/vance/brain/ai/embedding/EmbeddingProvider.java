package de.mhus.vance.brain.ai.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Builds a langchain4j {@link EmbeddingModel} for one provider. One
 * Spring bean per supported provider; {@link EmbeddingModelService}
 * indexes them by {@link #getName()} and dispatches.
 */
public interface EmbeddingProvider {

    /** Stable provider id, e.g. {@code "gemini"}. */
    String getName();

    /**
     * Whether this provider needs a non-blank
     * {@link EmbeddingConfig#apiKey()}. Default {@code true} — set to
     * {@code false} for in-process models (e.g. the {@code embedded}
     * provider) or for OpenAI-compatible endpoints that don't
     * authenticate (local Ollama / TEI). Drives the apiKey-presence
     * check in {@code RagService#resolveEmbeddingConfig}.
     */
    default boolean requiresApiKey() {
        return true;
    }

    /** Builds a fresh {@link EmbeddingModel} for one logical use. */
    EmbeddingModel createEmbeddingModel(EmbeddingConfig config);
}
