package de.mhus.vance.shared.storage;

import java.util.Date;
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
 * A single chunk of a stored blob. Many chunks share a {@code uuid} and an
 * index. Package-private — external code goes through {@link StorageService}.
 */
@Document(collection = "storage_data")
@CompoundIndexes({
        @CompoundIndex(name = "uuid_index_idx", def = "{ 'uuid': 1, 'index': 1 }", unique = true),
        // Orphan-storage sweep driver: iterate final chunks in createdAt order,
        // honouring the grace period via the createdAt range filter. Partial
        // filter keeps the index at one entry per blob (one final chunk per
        // uuid), not one per data chunk.
        @CompoundIndex(name = "final_createdAt_idx",
                def = "{ 'isFinal': 1, 'createdAt': 1 }",
                partialFilter = "{ 'isFinal': true }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class StorageData {

    @Id
    private @Nullable String id;

    /** Logical storage identifier (UUID). Returned to callers as {@code storageId}. */
    private String uuid = "";

    /** Original file path for reference. */
    private @Nullable String path;

    /** Tenant this blob belongs to (the {@code name} of the owning tenant). */
    private @Nullable String tenantId;

    /** Chunk index, 0-based, sequential. */
    private int index;

    /** Chunk payload — at most {@code chunkSize} bytes. */
    private byte @Nullable [] data;

    /** True on the last chunk — marks end of file and carries the total {@link #size}.
     *  Indexed via the {@code final_createdAt_idx} compound partial index. */
    private boolean isFinal;

    /** Total blob size in bytes. Only meaningful on the final chunk. */
    private long size;

    @CreatedDate
    private @Nullable Date createdAt;
}
