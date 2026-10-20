package de.mhus.vance.shared.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.cleanup.StorageBreakdownDto;
import de.mhus.vance.api.cleanup.StorageCleanupResultDto;
import de.mhus.vance.shared.document.DocumentArchiveDocument;
import de.mhus.vance.shared.document.DocumentArchiveService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.storage.StorageQuotaService;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Drives {@link StorageCleanupService} against in-memory fakes. The two
 * collaborators the cleanup actually exercises are {@link DocumentService}
 * and {@link DocumentArchiveService}; {@link MongoTemplate} is mocked for
 * the iteration cursors, and {@link StorageQuotaService} for the cache
 * refresh at the end of every cleanup.
 */
class StorageCleanupServiceTest {

    private DocumentService documentService;
    private DocumentArchiveService archiveService;
    private MongoTemplate mongoTemplate;
    private StorageQuotaService quotaService;
    private StorageCleanupService service;

    private static final String TENANT = "acme";
    private static final String PROJECT = "demo";

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        archiveService = mock(DocumentArchiveService.class);
        mongoTemplate = mock(MongoTemplate.class);
        quotaService = mock(StorageQuotaService.class);
        service = new StorageCleanupService(documentService, archiveService, mongoTemplate, quotaService);
    }

    // ────── summarize ──────

    @Test
    void summarize_composesLiveBytesAndReclaimableTotal() {
        when(quotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(
                        TENANT, /*used*/
                        1_000L, /*limit*/
                        2_000L,
                        /*percent*/ 50, /*exhausted*/
                        false, /*warning*/
                        false));

        // Stub the two find()s that back the per-scope top-archives lookup.
        when(mongoTemplate.find(any(Query.class), eq(DocumentArchiveDocument.class)))
                .thenReturn(List.of());
        stubEmptyAggregations();

        StorageBreakdownDto bd = service.summarize(TENANT, null);

        assertThat(bd.getTenant()).isEqualTo(TENANT);
        assertThat(bd.getProjectId()).isNull();
        assertThat(bd.getUsedBytes()).isEqualTo(1_000L);
        // No trash and no archives in this stub → liveBytes equals usedBytes.
        assertThat(bd.getLiveBytes()).isEqualTo(1_000L);
        assertThat(bd.getReclaimableBytes()).isZero();
        assertThat(bd.getLimitBytes()).isEqualTo(2_000L);
        assertThat(bd.getPercentUsed()).isEqualTo(50);
    }

    @Test
    void summarize_zeroUsage_returnsZeroCountsAndNegativePercent() {
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));
        when(mongoTemplate.find(any(Query.class), eq(DocumentArchiveDocument.class)))
                .thenReturn(List.of());
        stubEmptyAggregations();

        StorageBreakdownDto bd = service.summarize(TENANT, PROJECT);

        assertThat(bd.getProjectId()).isEqualTo(PROJECT);
        assertThat(bd.getUsedBytes()).isZero();
        assertThat(bd.getTrashBytes()).isZero();
        assertThat(bd.getArchiveBytes()).isZero();
        assertThat(bd.getPercentUsed()).isEqualTo(-1);
        // liveBytes must never go negative even when usedBytes=0 and the
        // unanswerable trash case lands us with usedBytes=0,trashBytes=0.
        assertThat(bd.getLiveBytes()).isZero();
    }

    // ────── empty trash ──────

    @Test
    void emptyTrash_deletesEveryTrashDocAndRefreshesQuota() {
        // Two batches worth of trash, then a short final batch to terminate.
        List<DocumentDocument> batch1 = List.of(trashDoc("doc-1", 100L), trashDoc("doc-2", 200L));
        List<DocumentDocument> batch2 = List.of(trashDoc("doc-3", 50L));
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(batch1)
                .thenReturn(batch2)
                .thenReturn(List.of());
        // The refresh at the end calls status() — supply a fresh read so
        // the response can carry the post-cleanup numbers.
        when(quotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 650L, 2_000L, 32, false, false));

        StorageCleanupResultDto result = service.emptyTrash(TENANT, null, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getAction()).isEqualTo("trash");
        assertThat(result.getDeletedEntries()).isEqualTo(3);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getFreedBytes()).isEqualTo(350L);
        verify(documentService, times(3))
                .delete(anyString(), any(DocumentService.WriterIdentity.class), any(WriteActor.class));

        // The refresh at the end MUST invalidate + re-fetch status, so the
        // UI can render the new numbers without waiting for the 30 s TTL.
        verify(quotaService, times(1)).invalidateAllCaches(TENANT);
        verify(quotaService, atLeastOnce()).status(TENANT);
    }

    @Test
    void emptyTrash_failedDeleteIsSkippedNotPropagated() {
        List<DocumentDocument> batch = List.of(trashDoc("doc-ok", 100L), trashDoc("doc-bad", 200L));
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class)))
                .thenReturn(batch)
                .thenReturn(List.of());

        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        // First delete succeeds, second throws — the iteration must keep
        // going so one bad row cannot stop a sweep.
        org.mockito.Mockito.doNothing()
                .when(documentService)
                .delete(eq("doc-ok"), any(DocumentService.WriterIdentity.class), any(WriteActor.class));
        org.mockito.Mockito.doThrow(new RuntimeException("storage down"))
                .when(documentService)
                .delete(eq("doc-bad"), any(DocumentService.WriterIdentity.class), any(WriteActor.class));

        StorageCleanupResultDto result = service.emptyTrash(TENANT, null, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getDeletedEntries()).isEqualTo(1);
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getFreedBytes()).isEqualTo(100L);
    }

    @Test
    void emptyTrash_emptyScope_noOpAndStillRefreshes() {
        when(mongoTemplate.find(any(Query.class), eq(DocumentDocument.class))).thenReturn(List.of());
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        StorageCleanupResultDto result = service.emptyTrash(TENANT, PROJECT, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getDeletedEntries()).isZero();
        assertThat(result.getFreedBytes()).isZero();
        verify(documentService, never())
                .delete(anyString(), any(DocumentService.WriterIdentity.class), any(WriteActor.class));
        // The refresh is unconditional — even an empty scope gets its
        // quota cache refreshed, so the UI is consistent either way.
        verify(quotaService, times(1)).invalidateAllCaches(TENANT);
    }

    // ────── thin archives ──────

    @Test
    void thinArchives_keepLatestFivePerLineage_deletesTheRest() {
        // Two lineages, 7 + 3 archives; keep=5 → 2 + 0 = 2 deletes.
        List<DocumentArchiveDocument> all = new ArrayList<>();
        all.add(archiveDoc("a1", "lin-A", "2024-01-07T00:00:00Z", 100));
        all.add(archiveDoc("a2", "lin-A", "2024-01-06T00:00:00Z", 100));
        all.add(archiveDoc("a3", "lin-A", "2024-01-05T00:00:00Z", 100));
        all.add(archiveDoc("a4", "lin-A", "2024-01-04T00:00:00Z", 100));
        all.add(archiveDoc("a5", "lin-A", "2024-01-03T00:00:00Z", 100));
        all.add(archiveDoc("a6", "lin-A", "2024-01-02T00:00:00Z", 100));
        all.add(archiveDoc("a7", "lin-A", "2024-01-01T00:00:00Z", 100));
        all.add(archiveDoc("b1", "lin-B", "2024-01-03T00:00:00Z", 50));
        all.add(archiveDoc("b2", "lin-B", "2024-01-02T00:00:00Z", 50));
        all.add(archiveDoc("b3", "lin-B", "2024-01-01T00:00:00Z", 50));

        when(mongoTemplate.stream(any(Query.class), eq(DocumentArchiveDocument.class)))
                .thenReturn(all.stream());
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        StorageCleanupResultDto result = service.thinArchives(TENANT, null, 5, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getAction()).isEqualTo("archives");
        assertThat(result.getKeepLatest()).isEqualTo(5);
        assertThat(result.getDeletedEntries()).isEqualTo(2);
        assertThat(result.getFreedBytes()).isEqualTo(200L);

        // Specifically: the two oldest entries of lin-A. B is untouched
        // because it has fewer than 5 entries.
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(archiveService, times(2)).deleteArchive(captor.capture());
        assertThat(captor.getAllValues()).containsExactlyInAnyOrder("a6", "a7");

        verify(quotaService, times(1)).invalidateAllCaches(TENANT);
    }

    @Test
    void thinArchives_zeroKeeps_removesEverything() {
        List<DocumentArchiveDocument> all = List.of(
                archiveDoc("a1", "lin-A", "2024-01-02T00:00:00Z", 100),
                archiveDoc("a2", "lin-A", "2024-01-01T00:00:00Z", 100));
        when(mongoTemplate.stream(any(Query.class), eq(DocumentArchiveDocument.class)))
                .thenReturn(all.stream());
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 0L, -1, false, false));

        StorageCleanupResultDto result = service.thinArchives(TENANT, null, 0, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getKeepLatest()).isZero();
        assertThat(result.getDeletedEntries()).isEqualTo(2);
        assertThat(result.getFreedBytes()).isEqualTo(200L);
    }

    @Test
    void thinArchives_clampsNegativeAndHugeKeepLatest() {
        // Below zero → default. Above MAX → MAX. Otherwise pass-through.
        assertThat(StorageCleanupService.clampKeepLatest(-1)).isEqualTo(StorageCleanupService.DEFAULT_KEEP_LATEST);
        assertThat(StorageCleanupService.clampKeepLatest(0)).isZero();
        assertThat(StorageCleanupService.clampKeepLatest(7)).isEqualTo(7);
        assertThat(StorageCleanupService.clampKeepLatest(99_999)).isEqualTo(StorageCleanupService.MAX_KEEP_LATEST);
    }

    // ────── fixtures ──────

    private static DocumentService.WriterIdentity testIdentity() {
        return new DocumentService.WriterIdentity("editor-cleanup-test", "u-1", "Cleanup Tester");
    }

    /**
     * Make every {@code aggregate(...)} call return an empty result. The
     * real driver would give {@code getUniqueMappedResult() == null} for an
     * empty bucket, which the service handles; the mock must surface a
     * non-null {@link AggregationResults} wrapper so the call does not NPE
     * before the null-check fires.
     */
    @SuppressWarnings("unchecked")
    private void stubEmptyAggregations() {
        AggregationResults<Document> empty = new AggregationResults<>(List.of(), new Document());
        when(mongoTemplate.aggregate(any(Aggregation.class), anyString(), any(Class.class)))
                .thenReturn(empty);
    }

    private static DocumentDocument trashDoc(String id, long size) {
        return DocumentDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path(DocumentService.TRASH_FOLDER_PREFIX + id + "_name.yaml")
                .name("name.yaml")
                .size(size)
                .status(DocumentStatus.ACTIVE)
                .build();
    }

    private static DocumentArchiveDocument archiveDoc(String id, String lineage, String iso, long size) {
        return DocumentArchiveDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .lineageId(lineage)
                .path("documents/foo.yaml")
                .size(size)
                .archivedAt(java.time.Instant.parse(iso))
                .build();
    }
}
