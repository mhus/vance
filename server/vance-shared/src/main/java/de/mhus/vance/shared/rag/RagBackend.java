package de.mhus.vance.shared.rag;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Storage and similarity-search seam for RAG chunks. v1 implementation
 * is {@link MongoRagBackend} (brute-force cosine over Mongo-stored
 * chunks); a real vector index (Atlas Vector Search, Qdrant, …) is a
 * second implementation with the same contract.
 *
 * <p>Embedding generation is <em>not</em> the backend's job — that
 * happens upstream in {@code vance-brain}'s {@code RagService}. The
 * backend only sees ready-to-store vectors and returns matches.
 */
public interface RagBackend {

    /** Adds chunks. Each must already carry its embedding vector. */
    void addChunks(List<RagChunkDocument> chunks);

    /**
     * Returns up to {@code topK} chunks most similar to {@code queryVector},
     * ordered by descending similarity score.
     */
    List<SearchHit> search(String tenantId, String ragId, float[] queryVector, int topK);

    /** Total number of chunks stored for {@code ragId}. */
    long count(String tenantId, String ragId);

    /** Deletes all chunks for a {@code (ragId, sourceRef)} pair. */
    long deleteBySource(String tenantId, String ragId, String sourceRef);

    /** Deletes every chunk of a RAG — used on RAG delete. */
    long deleteByRag(String tenantId, String ragId);

    /** Single search match with its similarity score. */
    record SearchHit(RagChunkDocument chunk, double score, @Nullable String reason) {
        public static SearchHit of(RagChunkDocument chunk, double score) {
            return new SearchHit(chunk, score, null);
        }
    }
}
