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

    /** Builds a fresh {@link EmbeddingModel} for one logical use. */
    EmbeddingModel createEmbeddingModel(EmbeddingConfig config);
}
