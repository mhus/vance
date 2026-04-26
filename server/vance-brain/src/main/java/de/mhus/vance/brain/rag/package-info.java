/**
 * Brain-side RAG orchestration. Bridges the persistence-layer
 * {@link de.mhus.vance.shared.rag.RagCatalogService} +
 * {@link de.mhus.vance.shared.rag.RagBackend} with the embedding
 * model. Single entry point: {@link
 * de.mhus.vance.brain.rag.RagService}.
 */
@NullMarked
package de.mhus.vance.brain.rag;

import org.jspecify.annotations.NullMarked;
