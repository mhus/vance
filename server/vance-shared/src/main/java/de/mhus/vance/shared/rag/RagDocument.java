package de.mhus.vance.shared.rag;

import java.time.Instant;
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
 * Catalog entry for a RAG. The {@code name} is the business id and
 * is unique per {@code (tenantId, projectId)} — that lets clients
 * reference RAGs by a stable, readable handle.
 *
 * <p>Embedding model + chunking config are pinned at creation: an
 * embedding model change would invalidate every existing chunk's
 * vector, so the catalog records what the chunks actually use and
 * any later "switch model" flow has to re-embed.
 */
@Document(collection = "rags")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_project_name_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'name': 1 }",
                unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String projectId = "";

    /** Business id — unique within {@code (tenantId, projectId)}. */
    private String name = "";

    private @Nullable String title;

    private @Nullable String description;

    /** Embedding provider name, e.g. {@code "gemini"}. */
    private String embeddingProvider = "";

    /** Embedding model name, e.g. {@code "text-embedding-004"}. */
    private String embeddingModel = "";

    /** Vector dimension produced by the embedding model. */
    private int embeddingDim;

    /** Char-based chunk size used for ingest. */
    private int chunkSize = 1_000;

    /** Char-based overlap between consecutive chunks. */
    private int chunkOverlap = 200;

    /** Denormalised counter — refreshed on add/delete for cheap UI reads. */
    private long chunkCount;

    @CreatedDate
    private @Nullable Instant createdAt;
}
