package de.mhus.vance.shared.document;

import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code document_archives} collection. Read+write entry point for
 * every operation on archived document versions:
 *
 * <ul>
 *   <li>{@link #archiveCurrent(DocumentDocument)} — snapshot the live row;
 *       pointer-move of the live document's {@code storageId} into the
 *       archive entry (no blob copy).</li>
 *   <li>{@link #restore(DocumentArchiveDocument)} — copy the archive's
 *       blob (or inline text) for the live document to swap in. The archive
 *       row stays untouched.</li>
 *   <li>{@link #deleteArchive(String)} — delete one archive row + its
 *       (now exclusively owned) blob.</li>
 *   <li>{@link #deleteAllForLineage(String, String, String)} — clean up
 *       every archive entry of a lineage when the live document is hard-deleted.</li>
 * </ul>
 *
 * <p>Storage-ownership rule (no reference counting): the {@code storageId}
 * referenced by an archive entry is owned exclusively by that entry — never
 * shared with the live document or with another archive. {@code archiveCurrent}
 * preserves this by <em>moving</em> the pointer; {@code restore} preserves it
 * by <em>duplicating</em> the blob; {@code deleteArchive} can therefore delete
 * the blob safely.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentArchiveService {

    private final DocumentArchiveRepository repository;
    private final StorageService storageService;

    /**
     * Snapshot {@code doc} into a new archive entry. The live document's
     * {@code storageId} pointer is moved over (the caller, typically
     * {@link DocumentService#update}, will replace it on the live row with
     * the fresh blob it's about to write). Inline content is copied as a
     * string (small, cheap).
     *
     * <p>Mutates the passed-in {@code doc} only by clearing its
     * {@code storageId} once successfully transferred — the caller must
     * not re-use that pointer afterwards.
     *
     * @return the persisted archive entry.
     */
    public DocumentArchiveDocument archiveCurrent(DocumentDocument doc) {
        if (doc.getId() == null) {
            throw new IllegalArgumentException(
                    "Cannot archive a transient document (id == null)");
        }
        if (doc.getLineageId() == null || doc.getLineageId().isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot archive document id='" + doc.getId()
                            + "' without lineageId");
        }
        String movedStorageId = doc.getStorageId();
        DocumentArchiveDocument entry = DocumentArchiveDocument.builder()
                .lineageId(doc.getLineageId())
                .originalDocumentId(doc.getId())
                .tenantId(doc.getTenantId())
                .projectId(doc.getProjectId())
                .path(doc.getPath())
                .name(doc.getName())
                .title(doc.getTitle())
                .tags(doc.getTags() == null
                        ? new java.util.ArrayList<>()
                        : new java.util.ArrayList<>(doc.getTags()))
                .mimeType(doc.getMimeType())
                .size(doc.getSize())
                .storageId(movedStorageId)
                .compressed(doc.isCompressed())
                .kind(doc.getKind())
                .headers(doc.getHeaders() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(doc.getHeaders()))
                .createdBy(doc.getCreatedBy())
                .archivedAt(Instant.now())
                .build();
        DocumentArchiveDocument saved = repository.save(entry);
        if (movedStorageId != null) {
            // The pointer is now exclusively owned by the archive entry.
            // Clear it on the in-memory live document so the caller cannot
            // accidentally delete it through StorageService.delete().
            doc.setStorageId(null);
        }
        log.info("Archived document tenantId='{}' projectId='{}' path='{}' lineageId='{}' archiveId='{}' archivedAt='{}'",
                saved.getTenantId(), saved.getProjectId(), saved.getPath(),
                saved.getLineageId(), saved.getId(), saved.getArchivedAt());
        return saved;
    }

    /**
     * Materialise the body of an archive entry. Inline entries return a
     * fresh {@link ByteArrayInputStream}; storage-backed entries return
     * the stream from {@link StorageService#load}. Caller closes.
     */
    public InputStream loadContent(DocumentArchiveDocument archive) {
        String sid = archive.getStorageId();
        if (sid == null) {
            return InputStream.nullInputStream();
        }
        InputStream stream = storageService.load(sid);
        if (stream == null) {
            log.warn("StorageService returned null for archive id='{}' storageId='{}'",
                    archive.getId(), sid);
            return InputStream.nullInputStream();
        }
        if (archive.isCompressed()) {
            try {
                return new GZIPInputStream(stream);
            } catch (IOException e) {
                log.warn("Failed to open gzip stream for archive id='{}' storageId='{}': {}",
                        archive.getId(), sid, e.toString());
                try { stream.close(); } catch (IOException ignored) { /* best effort */ }
                return InputStream.nullInputStream();
            }
        }
        return stream;
    }

    /**
     * Read the body of {@code archive} as a UTF-8 string. Returns an
     * empty string if the underlying blob is unreadable.
     */
    public String readContentAsString(DocumentArchiveDocument archive) {
        try (InputStream in = loadContent(archive)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read archive id='{}' lineageId='{}': {}",
                    archive.getId(), archive.getLineageId(), e.toString());
            return "";
        }
    }

    /**
     * Prepare a restore payload from {@code archive}. Inline entries return
     * the snapshot text as-is; storage-backed entries get a freshly
     * {@linkplain StorageService#duplicate duplicated} blob so the live
     * document never shares a blob with the archive.
     *
     * @return a {@link RestorePayload} the caller assigns onto the live
     *         document. Mutually exclusive: exactly one of
     *         {@link RestorePayload#inlineText} / {@link RestorePayload#storageId}
     *         is set.
     */
    public RestorePayload restore(DocumentArchiveDocument archive) {
        String sid = archive.getStorageId();
        if (sid == null) {
            // Empty archive — restoring yields an empty live document.
            return new RestorePayload(null, false, 0L,
                    archive.getMimeType(),
                    archive.getTitle(),
                    new java.util.ArrayList<>(archive.getTags()));
        }
        String dupId = storageService.duplicate(sid, archive.getTenantId());
        if (dupId == null) {
            throw new IllegalStateException(
                    "Failed to duplicate storage blob '" + sid + "' for restore — "
                            + "archive id='" + archive.getId() + "'");
        }
        // duplicate() copies raw bytes — gzip wrapper (if any) carries over,
        // so the compressed flag of the archive applies to the new blob too.
        return new RestorePayload(
                dupId,
                archive.isCompressed(),
                archive.getSize(),
                archive.getMimeType(),
                archive.getTitle(),
                new java.util.ArrayList<>(archive.getTags()));
    }

    /**
     * Delete one archive entry and its (exclusively owned) blob.
     * No-op if {@code archiveId} is unknown.
     */
    public void deleteArchive(String archiveId) {
        Optional<DocumentArchiveDocument> opt = repository.findById(archiveId);
        if (opt.isEmpty()) return;
        DocumentArchiveDocument archive = opt.get();
        String sid = archive.getStorageId();
        if (sid != null) {
            try {
                storageService.delete(sid);
            } catch (Exception e) {
                log.warn("Failed to delete storage blob for archive id='{}' storageId='{}'",
                        archiveId, sid, e);
            }
        }
        repository.delete(archive);
        log.info("Deleted archive id='{}' lineageId='{}' path='{}'",
                archive.getId(), archive.getLineageId(), archive.getPath());
    }

    /**
     * Wipe every archive entry for the lineage. Called by
     * {@link DocumentService#delete} when the live document is hard-deleted
     * so we don't leak archive rows + blobs that no live document points to.
     *
     * @return number of archive entries deleted.
     */
    public long deleteAllForLineage(String tenantId, String projectId, String lineageId) {
        if (lineageId == null || lineageId.isBlank()) return 0;
        List<DocumentArchiveDocument> archives =
                repository.findByTenantIdAndProjectIdAndLineageIdOrderByArchivedAtDesc(
                        tenantId, projectId, lineageId);
        for (DocumentArchiveDocument a : archives) {
            String sid = a.getStorageId();
            if (sid != null) {
                try {
                    storageService.delete(sid);
                } catch (Exception e) {
                    log.warn("Failed to delete storage blob for archive id='{}' storageId='{}'",
                            a.getId(), sid, e);
                }
            }
        }
        long deleted = repository.deleteByTenantIdAndProjectIdAndLineageId(
                tenantId, projectId, lineageId);
        if (deleted > 0) {
            log.info("Deleted {} archive entries for tenantId='{}' projectId='{}' lineageId='{}'",
                    deleted, tenantId, projectId, lineageId);
        }
        return deleted;
    }

    public Optional<DocumentArchiveDocument> findById(String archiveId) {
        return repository.findById(archiveId);
    }

    public long countForLineage(String tenantId, String projectId, String lineageId) {
        if (lineageId == null || lineageId.isBlank()) return 0;
        return repository.countByTenantIdAndProjectIdAndLineageId(tenantId, projectId, lineageId);
    }

    /** Archives for the lineage, newest first. */
    public List<DocumentArchiveDocument> listForLineage(
            String tenantId, String projectId, String lineageId) {
        if (lineageId == null || lineageId.isBlank()) return List.of();
        return repository.findByTenantIdAndProjectIdAndLineageIdOrderByArchivedAtDesc(
                tenantId, projectId, lineageId);
    }

    /**
     * Restore-payload carried back to {@link DocumentService}. Exactly one of
     * {@link #inlineText} and {@link #storageId} is non-null.
     */
    public record RestorePayload(
            @Nullable String storageId,
            boolean compressed,
            long size,
            @Nullable String mimeType,
            @Nullable String title,
            List<String> tags) {
    }
}
