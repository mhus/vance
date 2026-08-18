package de.mhus.vance.shared.storage;

import de.mhus.vance.shared.document.DocumentArchiveService;
import de.mhus.vance.shared.document.DocumentArchiveService.ArchiveOrphanCandidate;
import de.mhus.vance.shared.document.DocumentService;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Cluster-wide blob cleanup driven by {@code OrphanStorageSweepTick} on
 * the master pod only.
 *
 * <p>What it knows about is blobs. <b>Who</b> keeps a blob alive comes
 * from the registered {@link StorageReferenceSource}s — documents and
 * archives in a brain, releases in the kit store — so this class works
 * anywhere the chunked storage does. Archives are swept by their own
 * service in the document package; that is a statement about documents,
 * not about blobs.
 *
 * <p>Formerly two phases:
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

    private final StorageService storageService;

    /**
     * Everything that keeps a blob alive, in this deployment.
     *
     * <p>A list rather than two fixed collaborators: a brain contributes
     * documents and archives, the kit store contributes releases, and
     * anything that writes a blob without contributing a source would have
     * its data deleted — which is why an empty list stops the sweep
     * instead of licensing it.
     */
    private final List<StorageReferenceSource> referenceSources;

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
    public long sweepOnce(Instant now, Duration gracePeriod, int batchSize) {
        Instant cutoff = now.minus(gracePeriod);
        long storageDeleted = sweepOrphanStorage(cutoff, batchSize);
        if (storageDeleted > 0) {
            log.info("Storage-orphan sweep: storage={} (cutoff={})", storageDeleted, cutoff);
        }
        return storageDeleted;
    }

    long sweepOrphanStorage(Instant cutoff, int batchSize) {
        if (referenceSources.isEmpty()) {
            // Nothing claims to reference anything, which reads as "delete
            // everything". It never means that — it means this deployment
            // wired no source, and the honest answer is to do nothing and
            // say so.
            log.warn("Storage-orphan sweep: no reference source registered — skipping."
                    + " A deployment that stores blobs must contribute a"
                    + " StorageReferenceSource, or its data would look orphaned.");
            return 0;
        }
        long[] deleted = {0L};
        long[] failed = {0L};
        storageService.forEachFinalStorageIdOlderThan(cutoff, batchSize, batch -> {
            Set<String> referenced = referencedIn(batch);
            for (String sid : batch) {
                if (referenced.contains(sid)) continue;
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

    /**
     * Ask every source, and let one failure stop the run.
     *
     * <p>Skipping a source that could not answer would treat its blobs as
     * unreferenced and delete them. A failed sweep costs disk until the
     * next run; a half-blind sweep costs data that is gone. The exception
     * therefore propagates out of the batch callback and aborts the sweep.
     */
    private Set<String> referencedIn(Collection<String> batch) {
        Set<String> referenced = new HashSet<>();
        for (StorageReferenceSource source : referenceSources) {
            try {
                referenced.addAll(source.findReferencedStorageIds(batch));
            } catch (RuntimeException e) {
                throw new IllegalStateException("Storage-orphan sweep aborted: reference source '"
                        + source.sourceName() + "' could not answer — deleting on an incomplete"
                        + " answer would remove live blobs", e);
            }
        }
        return referenced;
    }

    /**
     * Treats {@code batch} as the sweep target — exposed for tests that
     * want to drive a single batch deterministically without spinning up
     * MongoDB. Production code uses {@link #sweepOnce}.
     */
    long checkOrphanStorageBatch(List<String> batch) {
        if (batch.isEmpty()) return 0;
        if (referenceSources.isEmpty()) return 0;
        Set<String> referenced = referencedIn(batch);
        long deleted = 0;
        for (String sid : batch) {
            if (referenced.contains(sid)) continue;
            storageService.delete(sid);
            deleted++;
        }
        return deleted;
    }
}
