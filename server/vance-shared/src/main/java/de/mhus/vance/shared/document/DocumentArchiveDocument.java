package de.mhus.vance.shared.document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One historical version of a {@link DocumentDocument}. Snapshots are written
 * by {@link DocumentArchiveService#archiveCurrent} just before
 * {@link DocumentService#update} overwrites the live row. All archive entries
 * for the same logical document share a {@link #lineageId}.
 *
 * <p>Storage lifecycle is asymmetric to keep archive cheap:
 * <ul>
 *   <li><b>Archive-on-write</b>: the live document's {@code storageId} is
 *       moved (pointer-copy) into the archive entry — no blob copy.</li>
 *   <li><b>Restore</b>: the archive stays as-is; the live document gets a
 *       fresh blob via {@link de.mhus.vance.shared.storage.StorageService#duplicate}.
 *       This avoids reference-counting between live and archive blobs.</li>
 * </ul>
 */
@Document(collection = "document_archives")
@CompoundIndexes({
        @CompoundIndex(
                name = "lineage_archived_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'lineageId': 1, 'archivedAt': -1 }"),
        @CompoundIndex(
                name = "lineage_idx",
                def = "{ 'lineageId': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentArchiveDocument {

    @Id
    private @Nullable String id;

    /** Shared with the live {@link DocumentDocument} and every other archive
     *  entry for the same logical document — survives renames, restores, etc. */
    private String lineageId = "";

    /** The id of the live document this entry was archived from at the time
     *  of archiving. The live document may be deleted afterwards; the lineageId
     *  remains the stable join key. */
    private String originalDocumentId = "";

    private String tenantId = "";

    private String projectId = "";

    /** Path the document had when this version was current. */
    private String path = "";

    private String name = "";

    private @Nullable String title;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private @Nullable String mimeType;

    private long size;

    /** Storage id moved over from the live document at archive-time. {@code null}
     *  when the archived version was inline-only. Sparse-indexed for the
     *  storage-orphan sweep's batch lookup. */
    @Indexed(sparse = true)
    private @Nullable String storageId;

    /** Mirrors {@link DocumentDocument#isCompressed()} — set on the archive at
     *  archive-time so {@link DocumentArchiveService#loadContent} can decompress
     *  entries consistently. */
    private boolean compressed;

    private @Nullable String kind;

    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();

    /** Original {@code createdBy} of the document (not the user who triggered
     *  the archive — that's not tracked separately in v1). */
    private @Nullable String createdBy;

    /** Wall-clock at which this version was archived. Also acts as the
     *  human-readable version label in the UI. */
    private Instant archivedAt = Instant.EPOCH;
}
