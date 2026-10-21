package de.mhus.vance.shared.cleanup;

import de.mhus.vance.api.cleanup.ArchiveWeightDto;
import de.mhus.vance.api.cleanup.StorageBreakdownDto;
import de.mhus.vance.api.cleanup.StorageCleanupResultDto;
import de.mhus.vance.shared.document.DocumentArchiveService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.SizeTotals;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.storage.StorageQuotaService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Manual cleanup of the two storage pools that count against the tenant
 * quota but can be released by an admin on demand: the project trash folder
 * and the archived document versions. Triggered from
 * Insights → Quotas when the storage meter turns red.
 *
 * <p><b>No auto-cleanup by design</b> (see {@code storage-quota.md} §6).
 * Which bytes count as "old" is a human call — Vance is not a
 * system-of-record for finished products. The admin clicks the button,
 * picks a depth (for archives) or just confirms (for trash), and this
 * service does the work.
 *
 * <p>The two operations here are very different in shape:
 * <ul>
 *   <li><b>Empty trash</b> — every document under {@value DocumentService#TRASH_FOLDER_PREFIX}
 *       gets hard-deleted. {@link DocumentService#delete} cascades through
 *       the archive line so the per-document cleanup is correct. Memory
 *       stays at O(batchSize) because we page through the trash.</li>
 *   <li><b>Thin archives</b> — for each lineage, sort archives by
 *       {@code archivedAt desc} and delete every entry beyond the
 *       configured keep-latest. The "keep latest N" algo is what we ship
 *       (see Quotas → cleanup dialog); older approaches like
 *       "older-than-X-days" wreck history on infrequently-edited but
 *       important documents.</li>
 * </ul>
 *
 * <p>Both operations end with the same dance — see
 * {@link #finishCleanup}: invalidate every quota cache, force a
 * re-aggregation, and bake the fresh numbers into the response. The
 * re-aggregation already leaves out blobs whose deletion is only scheduled
 * (see {@link StorageQuotaService}), so the freed bytes show at once.
 * Without that the UI's quota meter still shows the old number until the
 * 30 s {@code usageCache} TTL fires — which makes the cleanup feel broken
 * (button says "freed 200 MB", meter still red). Cross-pod coherence is
 * the existing 30 s TTL backstop; explicitly re-publishing
 * {@code DocumentChangedEvent}s is a separate spec.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageCleanupService {

    /** Page size for the trash iteration. */
    static final int CURSOR_BATCH = 500;

    /** Top-N heaviest archive lineages surfaced in the breakdown. */
    static final int TOP_ARCHIVES = 5;

    /** Server-side clamps for {@code keepLatest}. {@code 0} is legal — keep nothing — but only when sent explicitly. */
    public static final int MIN_KEEP_LATEST = 0;

    public static final int MAX_KEEP_LATEST = 500;
    public static final int DEFAULT_KEEP_LATEST = 5;

    private final DocumentService documentService;
    private final DocumentArchiveService archiveService;
    private final StorageQuotaService storageQuotaService;

    // ──────────────────── breakdown ────────────────────

    /**
     * Bytes breakdown for one scope. {@code projectId == null} means
     * "tenant-wide", otherwise the pools (live, trash, archives) are scoped
     * to the project. The quota header ({@code usedBytes / limitBytes /
     * percentUsed}) is always tenant-level — there is no per-project storage
     * quota (per {@code storage-quota.md} §1). On a tenant without a limit
     * {@code usedBytes} is still the real usage; limit and percent are
     * {@code -1}.
     */
    public StorageBreakdownDto summarize(String tenantId, @Nullable String projectId) {
        Instant now = Instant.now();
        QuotaHeader quota = quotaHeader(tenantId);

        SizeTotals live = documentService.sumLive(tenantId, projectId);
        SizeTotals trash = documentService.sumTrash(tenantId, projectId);
        SizeTotals archives = archiveService.sumSizes(tenantId, projectId);

        return StorageBreakdownDto.builder()
                .tenant(tenantId)
                .projectId(projectId)
                .usedBytes(quota.usedBytes())
                .trashBytes(trash.bytes())
                .archiveBytes(archives.bytes())
                .liveBytes(live.bytes())
                .reclaimableBytes(trash.bytes() + archives.bytes())
                .trashCount(trash.count())
                .archiveCount(archives.count())
                .topArchives(topArchives(tenantId, projectId))
                .limitBytes(quota.limitBytes())
                .percentUsed(quota.percentUsed())
                .computedAt(now)
                .build();
    }

    // ──────────────────── empty trash ────────────────────

    /**
     * Hard-delete every document under {@value DocumentService#TRASH_FOLDER_PREFIX}
     * in the given scope. The {@link DocumentService#delete} cascade wipes
     * each document's archives and storage blobs, so the bytes reclaimed per
     * document are its own {@code size} plus the sizes of its archive
     * entries — both are read before the delete.
     *
     * <p>Pages by {@code _id}: the trash path is not unique tenant-wide
     * (every project has its own trash folder), an id cursor is. A failed
     * delete is counted as skipped and not retried in the same run.
     *
     * <p>{@code WriterIdentity} / {@code WriteActor} are threaded so the
     * audit log names the admin who clicked the button, not SYSTEM.
     */
    public StorageCleanupResultDto emptyTrash(
            String tenantId, @Nullable String projectId, DocumentService.WriterIdentity identity, WriteActor actor) {
        long freedBytes = 0L;
        int deleted = 0;
        int skipped = 0;

        String afterId = null;
        while (true) {
            List<DocumentDocument> batch = documentService.listTrashPage(tenantId, projectId, afterId, CURSOR_BATCH);
            if (batch.isEmpty()) break;
            for (DocumentDocument doc : batch) {
                long bytes = doc.getSize()
                        + archiveService.sumSizeForLineage(doc.getTenantId(), doc.getProjectId(), doc.getLineageId());
                try {
                    documentService.delete(doc.getId(), identity, actor);
                    freedBytes += bytes;
                    deleted++;
                } catch (RuntimeException e) {
                    skipped++;
                    log.warn("Trash cleanup: delete doc id='{}' failed: {}", doc.getId(), e.toString());
                }
            }
            if (batch.size() < CURSOR_BATCH) break;
            afterId = batch.get(batch.size() - 1).getId();
        }

        log.info(
                "Trash cleanup: tenant='{}' project='{}' deleted={} skipped={} freed={}B",
                tenantId,
                projectId,
                deleted,
                skipped,
                freedBytes);

        return finishCleanup("trash", tenantId, freedBytes, deleted, skipped, 0);
    }

    // ──────────────────── thin archives ────────────────────

    /**
     * Keep the {@code keepLatest} newest archive entries per lineage, delete
     * the rest. {@code null} (field missing in the request) and negative
     * values fall back to {@link #DEFAULT_KEEP_LATEST}; values above
     * {@link #MAX_KEEP_LATEST} are capped. Only an explicit {@code 0} keeps
     * nothing.
     */
    public StorageCleanupResultDto thinArchives(
            String tenantId,
            @Nullable String projectId,
            @Nullable Integer keepLatest,
            DocumentService.WriterIdentity identity,
            WriteActor actor) {
        int keep = clampKeepLatest(keepLatest);

        List<DocumentArchiveService.ArchiveSize> toDelete =
                archiveService.findBeyondKeepLatest(tenantId, projectId, keep);

        long freedBytes = 0L;
        int deleted = 0;
        int skipped = 0;
        for (DocumentArchiveService.ArchiveSize a : toDelete) {
            try {
                // deleteArchive wipes the entry's exclusively-owned blob and
                // the row in one shot — no separate storageService.delete
                // call needed here.
                archiveService.deleteArchive(a.archiveId());
                freedBytes += a.size();
                deleted++;
            } catch (RuntimeException e) {
                skipped++;
                log.warn("Archive cleanup: deleteArchive id='{}' failed: {}", a.archiveId(), e.toString());
            }
            // identity/actor are kept in the signature for symmetry with
            // emptyTrash and so a future audit hook can name the admin who
            // triggered the cleanup (DocumentArchiveService.deleteArchive
            // itself does not thread them today).
        }

        log.info(
                "Archive cleanup: tenant='{}' project='{}' keep={} deleted={} skipped={} freed={}B",
                tenantId,
                projectId,
                keep,
                deleted,
                skipped,
                freedBytes);

        return finishCleanup("archives", tenantId, freedBytes, deleted, skipped, keep);
    }

    /** Clamp + default fallback for {@code keepLatest}. Exposed for tests. */
    public static int clampKeepLatest(@Nullable Integer keepLatest) {
        if (keepLatest == null || keepLatest < MIN_KEEP_LATEST) return DEFAULT_KEEP_LATEST;
        if (keepLatest > MAX_KEEP_LATEST) return MAX_KEEP_LATEST;
        return keepLatest;
    }

    // ──────────────────── internals ────────────────────

    /**
     * Drop every cache and re-aggregate so the response carries numbers the
     * UI can render without waiting for the {@code usageCache} TTL. This is
     * the explicit counterpart of the 30 s backstop the spec describes.
     */
    private StorageCleanupResultDto finishCleanup(
            String action, String tenantId, long freedBytes, int deleted, int skipped, int keep) {
        storageQuotaService.invalidateAllCaches(tenantId);
        QuotaHeader quota = quotaHeader(tenantId);

        return StorageCleanupResultDto.builder()
                .action(action)
                .freedBytes(freedBytes)
                .deletedEntries(deleted)
                .skipped(skipped)
                .keepLatest(keep)
                .limitBytes(quota.limitBytes())
                .usedBytes(quota.usedBytes())
                .percentUsed(quota.percentUsed())
                .build();
    }

    /**
     * Tenant quota figures for the response header. A tenant without a
     * limit still gets its real usage — {@link StorageQuotaService#status}
     * skips that aggregation — with limit and percent at {@code -1}.
     */
    private QuotaHeader quotaHeader(String tenantId) {
        StorageQuotaService.Status status = storageQuotaService.status(tenantId);
        if (status.limitBytes() > 0) {
            return new QuotaHeader(status.usedBytes(), status.limitBytes(), status.percentUsed());
        }
        return new QuotaHeader(storageQuotaService.currentUsage(tenantId), -1L, -1);
    }

    private record QuotaHeader(long usedBytes, long limitBytes, int percentUsed) {}

    /** One row per lineage — its largest version plus the lineage's version count. */
    private List<ArchiveWeightDto> topArchives(String tenantId, @Nullable String projectId) {
        return archiveService.heaviestLineages(tenantId, projectId, TOP_ARCHIVES).stream()
                .map(w -> ArchiveWeightDto.builder()
                        .archiveId(w.archiveId())
                        .lineageId(w.lineageId())
                        .path(w.path())
                        .size(w.size())
                        .archivedAt(w.archivedAt())
                        .versionCount(w.versionCount())
                        .build())
                .toList();
    }
}
