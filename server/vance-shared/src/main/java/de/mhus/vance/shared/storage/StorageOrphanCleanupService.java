package de.mhus.vance.shared.storage;

import de.mhus.vance.shared.document.DocumentArchiveService;
import de.mhus.vance.shared.document.DocumentArchiveService.ArchiveOrphanCandidate;
import de.mhus.vance.shared.document.DocumentService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Cluster-wide orphan cleanup driven by {@code OrphanStorageSweepTick} on
 * the master pod only. Two phases, in order:
 *
 * <ol>
 *   <li><b>Orphan archives</b> — archive entries whose {@code lineageId}
 *       has no live document left. Caused by a crashed
 *       {@code DocumentService.delete} that wiped the live row but didn't
 *       reach {@code deleteAllForLineage}. {@link DocumentArchiveService#deleteArchive}
 *       handles each entry plus its exclusively-owned blob.</li>
 *   <li><b>Orphan storage</b> — final-chunk blobs older than the grace
 *       period that no live document <em>and</em> no archive references.
 *       Soft-deleted via {@link StorageService#delete}; the existing
 *       {@code StorageCleanupScheduler} purges the chunks after the
 *       soft-delete window.</li>
 * </ol>
 *
 * <h2>Race-condition reasoning (kept here so future-me can re-validate)</h2>
 *
 * <p><b>Document write gap:</b> {@code DocumentService.create/update}
 * writes the blob first ({@code storageService.store}) and only then
 * persists the {@link de.mhus.vance.shared.document.DocumentDocument} row.
 * Between the two there exists a blob with no document pointer. The
 * orphan-storage phase guards against this with the {@code gracePeriod}
 * — only blobs with {@code createdAt < now − gracePeriod} are considered.
 *
 * <p><b>Archive write gap:</b> none. {@code archiveCurrent} does not
 * create fresh storage — it pointer-moves the live document's existing
 * {@code storageId} into the archive. Throughout the archive-save the
 * live document's Mongo row still references the same blob (the caller
 * updates the live row only <em>after</em> {@code archiveCurrent} returns).
 * At every instant, at least one of {live doc, archive} references the
 * blob. Therefore no grace period is needed for archive-orphan checks.
 *
 * <p><b>Hard-delete race:</b> {@code DocumentService.delete} runs in the
 * order (1) soft-delete live blob, (2) delete live document row, (3)
 * {@code deleteAllForLineage}. Between steps 2 and 3 archives exist with
 * no live document — the orphan-archive sweep would also delete them.
 * This is harmless: both paths converge on the same final state. If step
 * 3 crashes, the sweep is the recovery path — exactly its purpose.
 *
 * <h2>Memory</h2>
 *
 * <p>Both phases iterate through Mongo cursors batched at {@code batchSize}.
 * The JVM never holds more than O(batchSize) ids in memory regardless of
 * how many documents / archives / blobs the cluster has.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageOrphanCleanupService {

    private final DocumentService documentService;
    private final DocumentArchiveService archiveService;
    private final StorageService storageService;

    /**
     * Run both phases once. Caller (typically a scheduled tick) provides
     * {@code now} so tests can drive deterministically.
     *
     * @param now           reference instant for the grace cutoff
     * @param gracePeriod   blobs with {@code createdAt >= now − gracePeriod}
     *                      are spared (covers in-flight document writes)
     * @param batchSize     cursor batch size — sets the upper bound on
     *                      per-batch JVM memory and on the size of the
     *                      reverse-lookup {@code $in} queries
     */
    public CleanupResult sweepOnce(Instant now, Duration gracePeriod, int batchSize) {
        long archivesDeleted = sweepOrphanArchives(batchSize);
        Instant cutoff = now.minus(gracePeriod);
        long storageDeleted = sweepOrphanStorage(cutoff, batchSize);
        if (archivesDeleted > 0 || storageDeleted > 0) {
            log.info("Storage-orphan sweep: archives={} storage={} (cutoff={})",
                    archivesDeleted, storageDeleted, cutoff);
        }
        return new CleanupResult(archivesDeleted, storageDeleted);
    }

    long sweepOrphanArchives(int batchSize) {
        long[] deleted = {0L};
        long[] failed = {0L};
        archiveService.forEachArchive(batchSize, batch -> {
            Set<String> lineageIds = new HashSet<>();
            for (ArchiveOrphanCandidate c : batch) {
                if (c.lineageId() != null && !c.lineageId().isBlank()) {
                    lineageIds.add(c.lineageId());
                }
            }
            Set<String> alive = documentService.findLineageIdsWithLiveDocument(lineageIds);
            for (ArchiveOrphanCandidate c : batch) {
                boolean orphan = c.lineageId() == null
                        || c.lineageId().isBlank()
                        || !alive.contains(c.lineageId());
                if (!orphan) continue;
                try {
                    archiveService.deleteArchive(c.archiveId());
                    deleted[0]++;
                } catch (RuntimeException e) {
                    failed[0]++;
                    log.warn("Storage-orphan sweep: deleteArchive id='{}' failed: {}",
                            c.archiveId(), e.toString());
                }
            }
        });
        if (failed[0] > 0) {
            log.warn("Storage-orphan sweep: {} archive deletion(s) failed", failed[0]);
        }
        return deleted[0];
    }

    long sweepOrphanStorage(Instant cutoff, int batchSize) {
        long[] deleted = {0L};
        long[] failed = {0L};
        storageService.forEachFinalStorageIdOlderThan(cutoff, batchSize, batch -> {
            Set<String> refByDocs = documentService.findReferencedStorageIds(batch);
            Set<String> refByArchives = archiveService.findReferencedStorageIds(batch);
            for (String sid : batch) {
                if (refByDocs.contains(sid) || refByArchives.contains(sid)) continue;
                try {
                    storageService.delete(sid);
                    deleted[0]++;
                } catch (RuntimeException e) {
                    failed[0]++;
                    log.warn("Storage-orphan sweep: storageService.delete '{}' failed: {}",
                            sid, e.toString());
                }
            }
        });
        if (failed[0] > 0) {
            log.warn("Storage-orphan sweep: {} storage soft-delete(s) failed", failed[0]);
        }
        return deleted[0];
    }

    public record CleanupResult(long orphanArchivesDeleted, long orphanStorageDeleted) {
        public boolean isClean() {
            return orphanArchivesDeleted == 0 && orphanStorageDeleted == 0;
        }
    }

    /**
     * Treats {@code batch} as the sweep target — exposed for tests that
     * want to drive a single batch deterministically without spinning up
     * MongoDB. Production code uses {@link #sweepOnce}.
     */
    long checkOrphanStorageBatch(List<String> batch) {
        if (batch.isEmpty()) return 0;
        Set<String> refByDocs = documentService.findReferencedStorageIds(batch);
        Set<String> refByArchives = archiveService.findReferencedStorageIds(batch);
        long deleted = 0;
        for (String sid : batch) {
            if (refByDocs.contains(sid) || refByArchives.contains(sid)) continue;
            storageService.delete(sid);
            deleted++;
        }
        return deleted;
    }
}
