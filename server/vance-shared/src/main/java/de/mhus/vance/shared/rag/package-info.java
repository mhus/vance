/**
 * RAG (retrieval-augmented generation) persistence.
 *
 * <p>Two collections cooperate:
 * <ul>
 *   <li>{@code rags} ({@link de.mhus.vance.shared.rag.RagDocument}) —
 *       the catalog: per-project named RAG with its embedding model
 *       and chunking config.</li>
 *   <li>{@code rag_chunks}
 *       ({@link de.mhus.vance.shared.rag.RagChunkDocument}) —
 *       embedded text chunks belonging to one RAG.</li>
 * </ul>
 *
 * <p>{@link de.mhus.vance.shared.rag.RagBackend} is the seam between
 * "store and search vectors" and "manage the catalog". v1 ships
 * {@link de.mhus.vance.shared.rag.MongoRagBackend} which stores
 * chunks in Mongo and runs a streaming brute-force cosine on query
 * — fine up to ~50k chunks per RAG. A real vector index (Atlas
 * Vector Search, Qdrant, …) would be a second backend behind the
 * same interface.
 */
@NullMarked
package de.mhus.vance.shared.rag;

import org.jspecify.annotations.NullMarked;
