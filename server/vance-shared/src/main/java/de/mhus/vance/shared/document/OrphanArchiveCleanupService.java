package de.mhus.vance.shared.document;

import de.mhus.vance.shared.document.DocumentArchiveService.ArchiveOrphanCandidate;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Archive entries whose lineage has no live document left.
 *
 * <p>Split out of the blob sweep because it is a statement about
 * documents, not about storage: it asks whether an archived version still
 * belongs to anything, and it exists only where documents do. A deployment
 * that stores blobs without documents — the kit store — has no archives to
 * sweep and no reason to carry this.
 *
 * <p>Cause of such orphans: a crashed {@code DocumentService.delete} that
 * wiped the live row but never reached {@code deleteAllForLineage}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrphanArchiveCleanupService {

    private final DocumentService documentService;
    private final DocumentArchiveService archiveService;

    public long sweepOnce(int batchSize) {
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

}
