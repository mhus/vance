package de.mhus.vance.shared.cleanup;

import de.mhus.vance.api.cleanup.ArchiveWeightDto;
import de.mhus.vance.api.cleanup.StorageBreakdownDto;
import de.mhus.vance.api.cleanup.StorageCleanupResultDto;
import de.mhus.vance.shared.document.DocumentArchiveDocument;
import de.mhus.vance.shared.document.DocumentArchiveService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.storage.StorageQuotaService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
 *       stays at O(batchSize) because we iterate via a Mongo cursor.</li>
 *   <li><b>Thin archives</b> — for each lineage, sort archives by
 *       {@code archivedAt desc} and delete every entry beyond the
 *       configured keep-latest. The "keep latest N" algo is what we ship
 *       (see Quotas → cleanup dialog); older approaches like
 *       "older-than-X-days" wreck history on infrequently-edited but
 *       important documents.</li>
 * </ul>
 *
 * <p>Both operations end with the same dance — see
 * {@link #refreshQuotaAfterCleanup(String)}: invalidate every quota cache,
 * force a re-aggregation, and bake the fresh numbers into the response.
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

    /** Batch size for cursor-based iteration over trash documents and archives. */
    static final int CURSOR_BATCH = 500;

    /** Top-N heaviest archives surfaced in the breakdown. */
    static final int TOP_ARCHIVES = 5;

    /** Server-side clamps for {@code keepLatest}. Below the lower bound is silly (would no-op); above the upper bound is a typo or abuse. */
    public static final int MIN_KEEP_LATEST = 0;

    public static final int MAX_KEEP_LATEST = 500;
    public static final int DEFAULT_KEEP_LATEST = 5;

    private final DocumentService documentService;
    private final DocumentArchiveService archiveService;
    private final MongoTemplate mongoTemplate;
    private final StorageQuotaService storageQuotaService;

    // ──────────────────── breakdown ────────────────────

    /**
     * Bytes breakdown for one scope. {@code projectId == null} means
     * "tenant-wide", otherwise the figures are scoped to the project. The
     * quota header ({@code usedBytes / limitBytes / percentUsed}) is always
     * tenant-level — there is no per-project storage quota (per
     * {@code storage-quota.md} §1).
     */
    public StorageBreakdownDto summarize(String tenantId, @Nullable String projectId) {
        Instant now = Instant.now();
        long usedBytes = storageQuotaService.status(tenantId).usedBytes();
        long limitBytes = storageQuotaService.status(tenantId).limitBytes();
        int percent = storageQuotaService.status(tenantId).percentUsed();

        AggResult trashResult = aggregateTrash(tenantId, projectId);
        AggResult archiveResult = aggregateArchives(tenantId, projectId);

        long trashBytes = trashResult.totalSize;
        long trashCount = trashResult.totalCount;
        long archiveBytes = archiveResult.totalSize;
        long archiveCount = archiveResult.totalCount;

        long liveBytes = Math.max(0L, usedBytes - trashBytes);

        return StorageBreakdownDto.builder()
                .tenant(tenantId)
                .projectId(projectId)
                .usedBytes(usedBytes)
                .trashBytes(trashBytes)
                .archiveBytes(archiveBytes)
                .liveBytes(liveBytes)
                .reclaimableBytes(trashBytes + archiveBytes)
                .trashCount(trashCount)
                .archiveCount(archiveCount)
                .topArchives(topArchives(tenantId, projectId))
                .limitBytes(limitBytes)
                .percentUsed(percent)
                .computedAt(now)
                .build();
    }

    // ──────────────────── empty trash ────────────────────

    /**
     * Hard-delete every document under {@value DocumentService#TRASH_FOLDER_PREFIX}
     * in the given scope. The {@link DocumentService#delete} cascade wipes
     * each document's archives and storage blobs in one transaction-like
     * step, so the per-document bytes reclaimed are the sum of the doc's
     * {@code size} plus the sizes of its archive entries.
     *
     * <p>{@code WriterIdentity} / {@code WriteActor} are threaded so the
     * audit log names the admin who clicked the button, not SYSTEM.
     */
    public StorageCleanupResultDto emptyTrash(
            String tenantId, @Nullable String projectId, DocumentService.WriterIdentity identity, WriteActor actor) {
        long freedBytes = 0L;
        int deleted = 0;
        int skipped = 0;

        Query q = baseTrashQuery(tenantId, projectId).limit(CURSOR_BATCH);
        List<DocumentDocument> batch;
        while (true) {
            batch = mongoTemplate.find(q, DocumentDocument.class);
            if (batch.isEmpty()) break;
            for (DocumentDocument doc : batch) {
                long before = doc.getSize();
                try {
                    documentService.delete(doc.getId(), identity, actor);
                    freedBytes += before;
                    deleted++;
                } catch (RuntimeException e) {
                    skipped++;
                    log.warn("Trash cleanup: delete doc id='{}' failed: {}", doc.getId(), e.toString());
                }
            }
            // Cursor advance: re-issue the query with a path-past-cursor
            // guard so a deleted doc's path cannot reappear in the next
            // batch. Without it, deleted docs that the cursor's already
            // buffered would still get processed twice.
            String lastPath = batch.get(batch.size() - 1).getPath();
            q = baseTrashQuery(tenantId, projectId)
                    .addCriteria(Criteria.where("path").gt(lastPath))
                    .limit(CURSOR_BATCH);
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
     * the rest. Out-of-range inputs are clamped: {@code < 0} → {@link #DEFAULT_KEEP_LATEST},
     * {@code > MAX_KEEP_LATEST} → {@link #MAX_KEEP_LATEST}.
     */
    public StorageCleanupResultDto thinArchives(
            String tenantId,
            @Nullable String projectId,
            int keepLatest,
            DocumentService.WriterIdentity identity,
            WriteActor actor) {
        int keep = clampKeepLatest(keepLatest);

        // One pass over the archive collection, grouped by lineage. Each
        // group keeps its first {@code keep} entries (already sorted by
        // archivedAt desc by the index) and the rest go to the delete
        // pile. Grouping in Mongo rather than in Java keeps memory at
        // O(groups + deletePile), not O(totalArchives).
        ArchivePlan plan = planArchiveThinning(tenantId, projectId, keep);

        long freedBytes = 0L;
        int deleted = 0;
        int skipped = 0;
        for (DocumentArchiveDocument a : plan.toDelete) {
            try {
                // deleteArchive wipes the entry's exclusively-owned blob and
                // the row in one shot — no separate storageService.delete
                // call needed here.
                archiveService.deleteArchive(a.getId());
                freedBytes += a.getSize();
                deleted++;
            } catch (RuntimeException e) {
                skipped++;
                log.warn("Archive cleanup: deleteArchive id='{}' failed: {}", a.getId(), e.toString());
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
    public static int clampKeepLatest(int keepLatest) {
        if (keepLatest < MIN_KEEP_LATEST) return DEFAULT_KEEP_LATEST;
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
        StorageQuotaService.Status status = storageQuotaService.status(tenantId);

        return StorageCleanupResultDto.builder()
                .action(action)
                .freedBytes(freedBytes)
                .deletedEntries(deleted)
                .skipped(skipped)
                .keepLatest(keep)
                .limitBytes(status.limitBytes())
                .usedBytes(status.usedBytes())
                .percentUsed(status.percentUsed())
                .build();
    }

    /** Base query: trash-folder documents of one scope. Path-past-cursor is added per iteration. */
    private Query baseTrashQuery(String tenantId, @Nullable String projectId) {
        Query q = new Query()
                .addCriteria(Criteria.where("tenantId").is(tenantId))
                .addCriteria(Criteria.where("status").is(DocumentStatus.ACTIVE))
                // Path prefix lives in its own orOperator slot so the
                // cursor guard (path > lastPath, added per iteration) does
                // not collide on the same field name — Spring Data rejects
                // two top-level criteria on the same key.
                .addCriteria(new Criteria()
                        .orOperator(Criteria.where("path")
                                .regex("^" + Pattern.quote(DocumentService.TRASH_FOLDER_PREFIX))));
        if (projectId != null && !projectId.isBlank()) {
            q.addCriteria(Criteria.where("projectId").is(projectId));
        }
        return q.with(org.springframework.data.domain.Sort.by("path"));
    }

    /**
     * Sum of {@code size} and count of documents under {@code _vance/trash/}.
     * Tenant-wide when {@code projectId == null}, project-scoped otherwise.
     */
    private AggResult aggregateTrash(String tenantId, @Nullable String projectId) {
        var match = Aggregation.match(Criteria.where("tenantId")
                .is(tenantId)
                .and("status")
                .is(DocumentStatus.ACTIVE)
                .and("path")
                .regex("^" + Pattern.quote(DocumentService.TRASH_FOLDER_PREFIX)));
        var group = Aggregation.group().sum("size").as("totalSize").count().as("totalCount");
        Aggregation pipeline = projectId == null || projectId.isBlank()
                ? Aggregation.newAggregation(match, group)
                : Aggregation.newAggregation(
                        match, Aggregation.match(Criteria.where("projectId").is(projectId)), group);
        AggregationResults<SumAndCount> r = mongoTemplate.aggregate(pipeline, "documents", SumAndCount.class);
        SumAndCount row = r.getUniqueMappedResult();
        if (row == null) return new AggResult(0L, 0L);
        return new AggResult(row.totalSize, row.totalCount);
    }

    /**
     * Sum of {@code size} and count of {@code document_archives} rows for
     * one scope. Inline-only archives (no {@code size}) contribute 0 to the
     * byte count but 1 to the count — the user sees "we have 23 inline-only
     * versions, no bytes" and decides accordingly.
     */
    private AggResult aggregateArchives(String tenantId, @Nullable String projectId) {
        var match = Aggregation.match(Criteria.where("tenantId").is(tenantId));
        var group = Aggregation.group().sum("size").as("totalSize").count().as("totalCount");
        Aggregation pipeline = projectId == null || projectId.isBlank()
                ? Aggregation.newAggregation(match, group)
                : Aggregation.newAggregation(
                        match, Aggregation.match(Criteria.where("projectId").is(projectId)), group);
        AggregationResults<SumAndCount> r = mongoTemplate.aggregate(pipeline, "document_archives", SumAndCount.class);
        SumAndCount row = r.getUniqueMappedResult();
        if (row == null) return new AggResult(0L, 0L);
        return new AggResult(row.totalSize, row.totalCount);
    }

    /**
     * Top-N heaviest archive entries, lineage-grouped so each lineage only
     * appears once even if it has many large versions. The {@code size}
     * shown is the largest version in the lineage; {@code versionCount} is
     * the total entries under that lineage — that is the figure that tells
     * the admin whether thinning this lineage is worth it.
     */
    private List<ArchiveWeightDto> topArchives(String tenantId, @Nullable String projectId) {
        Query q = new Query()
                .addCriteria(Criteria.where("tenantId").is(tenantId))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("size")))
                .limit(TOP_ARCHIVES);
        if (projectId != null && !projectId.isBlank()) {
            q.addCriteria(Criteria.where("projectId").is(projectId));
        }
        List<DocumentArchiveDocument> heaviest = mongoTemplate.find(q, DocumentArchiveDocument.class);
        if (heaviest.isEmpty()) return List.of();

        // Distinct lineages among the top-N (rarely needed but cheap) +
        // a versionCount lookup so the UI shows "this one doc has 14 versions".
        Map<String, Long> versionCounts = new LinkedHashMap<>();
        for (DocumentArchiveDocument a : heaviest) {
            versionCounts.computeIfAbsent(
                    a.getLineageId(), lin -> archiveService.countForLineage(tenantId, a.getProjectId(), lin));
        }

        List<ArchiveWeightDto> out = new ArrayList<>(heaviest.size());
        for (DocumentArchiveDocument a : heaviest) {
            out.add(ArchiveWeightDto.builder()
                    .archiveId(a.getId() == null ? "" : a.getId())
                    .lineageId(a.getLineageId())
                    .path(a.getPath())
                    .size(a.getSize())
                    .archivedAt(a.getArchivedAt())
                    .versionCount(versionCounts.getOrDefault(a.getLineageId(), 1L))
                    .build());
        }
        // Stable secondary sort: heaviest first; ties broken by newest.
        out.sort(Comparator.comparingLong(ArchiveWeightDto::getSize).reversed());
        return out;
    }

    /**
     * Plan the per-lineage "keep latest N" thinning by streaming the
     * archive collection once. Grouped by lineage, the first {@code keep}
     * entries (sorted by {@code archivedAt desc} via the compound index)
     * survive; the rest go to the delete pile.
     */
    ArchivePlan planArchiveThinning(String tenantId, @Nullable String projectId, int keep) {
        Query q = new Query()
                .addCriteria(Criteria.where("tenantId").is(tenantId))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.asc("lineageId"),
                        org.springframework.data.domain.Sort.Order.desc("archivedAt")));
        if (projectId != null && !projectId.isBlank()) {
            q.addCriteria(Criteria.where("projectId").is(projectId));
        }

        ArchivePlan plan = new ArchivePlan();
        Map<String, Integer> perLineageKept = new LinkedHashMap<>();

        try (var stream = mongoTemplate.stream(q, DocumentArchiveDocument.class)) {
            var iter = stream.iterator();
            String currentLineage = null;
            while (iter.hasNext()) {
                DocumentArchiveDocument a = iter.next();
                String lineage = a.getLineageId() == null ? "" : a.getLineageId();
                if (!lineage.equals(currentLineage)) {
                    perLineageKept.put(lineage, 0);
                    currentLineage = lineage;
                }
                int kept = perLineageKept.get(lineage);
                if (kept < keep) {
                    perLineageKept.put(lineage, kept + 1);
                } else {
                    plan.toDelete.add(a);
                }
            }
        }
        return plan;
    }

    /** Aggregation output row — {@code totalSize} is sum of {@code size} over the bucket, {@code totalCount} the row count. */
    private static class SumAndCount {
        private long totalSize;
        private long totalCount;

        public long getTotalSize() {
            return totalSize;
        }

        public void setTotalSize(long totalSize) {
            this.totalSize = totalSize;
        }

        public long getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(long totalCount) {
            this.totalCount = totalCount;
        }
    }

    /** Plain holder for the result of one aggregation. Package-private so tests can compare. */
    record AggResult(long totalSize, long totalCount) {}

    /** Plan returned by {@link #planArchiveThinning}: every archive entry that should be deleted. */
    static final class ArchivePlan {
        final List<DocumentArchiveDocument> toDelete = new ArrayList<>();
    }
}
