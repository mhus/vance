package de.mhus.vance.brain.ai.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed embeddings via langchain4j's
 * {@link GoogleAiEmbeddingModel}. Default model in our setup:
 * {@code text-embedding-004} (768-dim).
 */
@Component
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    public static final String NAME = "gemini";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public EmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
        return GoogleAiEmbeddingModel.builder()
                .modelName(config.modelName())
                .apiKey(config.apiKey())
                .build();
    }
}
