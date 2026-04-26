package de.mhus.vance.shared.rag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One embedded chunk of a RAG. {@code embedding} is stored as a
 * {@code List<Float>} for Mongo round-trip friendliness; the search
 * layer materialises it into a {@code float[]} for the cosine
 * computation.
 *
 * <p>{@code sourceRef} ties multiple chunks back to the source they
 * were carved from (file path, URL, free-form tag). It enables
 * targeted re-ingest / delete by source without touching the rest
 * of the RAG.
 */
@Document(collection = "rag_chunks")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_rag_idx",
                def = "{ 'tenantId': 1, 'ragId': 1 }"),
        @CompoundIndex(name = "tenant_rag_source_idx",
                def = "{ 'tenantId': 1, 'ragId': 1, 'sourceRef': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChunkDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String projectId = "";

    /** {@code RagDocument.id} this chunk belongs to. */
    private String ragId = "";

    /** Optional logical source id (file path, URL, tag). */
    private @Nullable String sourceRef;

    /** Position of this chunk within its source (0-based). */
    private int position;

    private String content = "";

    /** Embedding vector — same {@code embeddingDim} as the parent RAG. */
    @Builder.Default
    private List<Float> embedding = List.of();

    /** Free-form per-chunk metadata. */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @CreatedDate
    private @Nullable Instant createdAt;
}
