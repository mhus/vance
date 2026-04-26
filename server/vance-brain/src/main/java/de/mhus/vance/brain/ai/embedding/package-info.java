/**
 * Embedding-model abstraction parallel to {@link
 * de.mhus.vance.brain.ai.AiModelService}. Discovers
 * {@link de.mhus.vance.brain.ai.embedding.EmbeddingProvider} Spring
 * beans and dispatches by provider name; the result is a
 * langchain4j {@link dev.langchain4j.model.embedding.EmbeddingModel}
 * which the RAG layer uses to embed both ingest text and queries.
 */
@NullMarked
package de.mhus.vance.brain.ai.embedding;

import org.jspecify.annotations.NullMarked;
