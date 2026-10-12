package de.mhus.vance.brain.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmbeddingConfigTest {

    @Test
    void toString_masksTheApiKey() {
        EmbeddingConfig cfg = new EmbeddingConfig("openai", "text-embedding-3-small", "sk-secret-123");

        assertThat(cfg.toString()).doesNotContain("sk-secret-123").contains("key=***");
    }

    @Test
    void toString_saysNoneForAnEmptyKey() {
        EmbeddingConfig cfg = new EmbeddingConfig("ollama", "nomic-embed-text", "", "http://localhost:11434");

        assertThat(cfg.toString()).contains("key=none");
    }
}
