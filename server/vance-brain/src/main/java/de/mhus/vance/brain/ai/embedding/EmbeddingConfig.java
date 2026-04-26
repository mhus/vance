package de.mhus.vance.brain.ai.embedding;

/**
 * Per-call embedding-model wiring. Mirrors the shape of
 * {@link de.mhus.vance.brain.ai.AiChatConfig} so callers can resolve
 * settings the same way for both chat and embeddings.
 */
public record EmbeddingConfig(String provider, String modelName, String apiKey) {
}
