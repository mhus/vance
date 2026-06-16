package de.mhus.vance.brain.ai.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * OpenAI-backed embeddings via langchain4j's
 * {@link OpenAiEmbeddingModel}. Honours
 * {@link EmbeddingConfig#baseUrl()}, so the same bean covers OpenAI
 * proper and any OpenAI-compatible gateway (Ollama, TEI, vLLM,
 * cortecs, …). Typical models: {@code text-embedding-3-small} (1536
 * dim), {@code text-embedding-3-large} (3072 dim), or whatever a
 * compat endpoint exposes (e.g. {@code nomic-embed-text} on Ollama).
 */
@Component
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    public static final String NAME = "openai";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public EmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.modelName());
        if (config.baseUrl() != null) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }
}
