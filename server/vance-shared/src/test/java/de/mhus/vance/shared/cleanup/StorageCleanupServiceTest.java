package de.mhus.vance.shared.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.cleanup.StorageBreakdownDto;
import de.mhus.vance.api.cleanup.StorageCleanupResultDto;
import de.mhus.vance.shared.document.DocumentArchiveService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.document.SizeTotals;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.storage.StorageQuotaService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Drives {@link StorageCleanupService} against mocked collaborators. The
 * service owns no data — trash and live sums come from
 * {@link DocumentService}, archive sums and the thinning plan from
 * {@link DocumentArchiveService}, quota figures from
 * {@link StorageQuotaService} — so the tests check composition, paging,
 * byte accounting and the cache refresh at the end of every cleanup.
 */
class StorageCleanupServiceTest {

    private DocumentService documentService;
    private DocumentArchiveService archiveService;
    private StorageQuotaService quotaService;
    private StorageCleanupService service;

    private static final String TENANT = "acme";
    private static final String PROJECT = "demo";

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        archiveService = mock(DocumentArchiveService.class);
        quotaService = mock(StorageQuotaService.class);
        service = new StorageCleanupService(documentService, archiveService, quotaService);
        when(documentService.sumLive(anyString(), any())).thenReturn(SizeTotals.EMPTY);
        when(documentService.sumTrash(anyString(), any())).thenReturn(SizeTotals.EMPTY);
        when(archiveService.sumSizes(anyString(), any())).thenReturn(SizeTotals.EMPTY);
        when(archiveService.heaviestLineages(anyString(), any(), anyInt())).thenReturn(List.of());
    }

    // ────── summarize ──────

    @Test
    void summarize_composesPoolsOfTheScopeAndTenantQuotaHeader() {
        when(quotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 1_000L, 2_000L, 50, false, false));
        when(documentService.sumLive(TENANT, PROJECT)).thenReturn(new SizeTotals(300L, 3));
        when(documentService.sumTrash(TENANT, PROJECT)).thenReturn(new SizeTotals(120L, 2));
        when(archiveService.sumSizes(TENANT, PROJECT)).thenReturn(new SizeTotals(80L, 4));

        StorageBreakdownDto bd = service.summarize(TENANT, PROJECT);

        assertThat(bd.getProjectId()).isEqualTo(PROJECT);
        // usedBytes is the tenant quota counter, the pools are the project's.
        assertThat(bd.getUsedBytes()).isEqualTo(1_000L);
        assertThat(bd.getLiveBytes()).isEqualTo(300L);
        assertThat(bd.getTrashBytes()).isEqualTo(120L);
        assertThat(bd.getTrashCount()).isEqualTo(2);
        assertThat(bd.getArchiveBytes()).isEqualTo(80L);
        assertThat(bd.getArchiveCount()).isEqualTo(4);
        assertThat(bd.getReclaimableBytes()).isEqualTo(200L);
        assertThat(bd.getLimitBytes()).isEqualTo(2_000L);
        assertThat(bd.getPercentUsed()).isEqualTo(50);
        verify(quotaService, never()).currentUsage(anyString());
    }

    @Test
    void summarize_unlimitedTenant_reportsRealUsageAndMinusOneLimit() {
        // status() skips the aggregation for an unlimited tenant and says -1;
        // the breakdown asks for the real number instead of passing that on.
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, -1L, 0L, -1, false, false));
        when(quotaService.currentUsage(TENANT)).thenReturn(4_200L);

        StorageBreakdownDto bd = service.summarize(TENANT, null);

        assertThat(bd.getUsedBytes()).isEqualTo(4_200L);
        assertThat(bd.getLimitBytes()).isEqualTo(-1L);
        assertThat(bd.getPercentUsed()).isEqualTo(-1);
    }

    @Test
    void summarize_topArchivesAreOneRowPerLineage() {
        when(quotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 10L, 20L, 50, false, false));
        when(archiveService.heaviestLineages(TENANT, null, StorageCleanupService.TOP_ARCHIVES))
                .thenReturn(List.of(new DocumentArchiveService.LineageWeight(
                        "arc-1", PROJECT, "lin-A", "notes/big.md", 900L, Instant.EPOCH, 14L)));

        StorageBreakdownDto bd = service.summarize(TENANT, null);

        assertThat(bd.getTopArchives()).hasSize(1);
        assertThat(bd.getTopArchives().get(0).getArchiveId()).isEqualTo("arc-1");
        assertThat(bd.getTopArchives().get(0).getLineageId()).isEqualTo("lin-A");
        assertThat(bd.getTopArchives().get(0).getVersionCount()).isEqualTo(14L);
    }

    // ────── empty trash ──────

    @Test
    void emptyTrash_pagesByIdUntilAShortPageAndCountsArchiveBytes() {
        // A full page (CURSOR_BATCH rows) forces a second request with the
        // last id as cursor; the short second page ends the run.
        List<DocumentDocument> page1 = new ArrayList<>();
        for (int i = 0; i < StorageCleanupService.CURSOR_BATCH; i++) {
            page1.add(trashDoc("doc-" + i, "lin-" + i, 1L));
        }
        List<DocumentDocument> page2 = List.of(trashDoc("doc-last", "lin-last", 50L));
        String lastOfPage1 = page1.get(page1.size() - 1).getId();
        when(documentService.listTrashPage(eq(TENANT), isNull(), isNull(), eq(StorageCleanupService.CURSOR_BATCH)))
                .thenReturn(page1);
        when(documentService.listTrashPage(
                        eq(TENANT), isNull(), eq(lastOfPage1), eq(StorageCleanupService.CURSOR_BATCH)))
                .thenReturn(page2);
        // The delete cascades the lineage's archives — their bytes are freed too.
        when(archiveService.sumSizeForLineage(TENANT, PROJECT, "lin-last")).thenReturn(1_000L);
        when(quotaService.status(TENANT))
                .thenReturn(new StorageQuotaService.Status(TENANT, 650L, 2_000L, 32, false, false));

        StorageCleanupResultDto result = service.emptyTrash(TENANT, null, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getAction()).isEqualTo("trash");
        assertThat(result.getDeletedEntries()).isEqualTo(StorageCleanupService.CURSOR_BATCH + 1);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getFreedBytes()).isEqualTo(StorageCleanupService.CURSOR_BATCH + 50L + 1_000L);
        verify(documentService, times(2)).listTrashPage(eq(TENANT), isNull(), any(), anyInt());

        // The refresh at the end MUST invalidate + re-fetch status, so the
        // UI can render the new numbers without waiting for the 30 s TTL.
        verify(quotaService, times(1)).invalidateAllCaches(TENANT);
        verify(quotaService, atLeastOnce()).status(TENANT);
        assertThat(result.getUsedBytes()).isEqualTo(650L);
    }

    @Test
    void emptyTrash_failedDeleteIsSkippedNotPropagated() {
        when(documentService.listTrashPage(eq(TENANT), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(trashDoc("doc-ok", "lin-ok", 100L), trashDoc("doc-bad", "lin-bad", 200L)));
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 1L, 0, false, false));

        // First delete succeeds, second throws — the iteration must keep
        // going so one bad row cannot stop a sweep.
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
        when(documentService.listTrashPage(eq(TENANT), eq(PROJECT), isNull(), anyInt()))
                .thenReturn(List.of());
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 1L, 0, false, false));

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
    void thinArchives_deletesThePlannedPileAndSumsItsBytes() {
        when(archiveService.findBeyondKeepLatest(TENANT, null, 5))
                .thenReturn(List.of(
                        new DocumentArchiveService.ArchiveSize("a6", 100L),
                        new DocumentArchiveService.ArchiveSize("a7", 100L)));
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 1L, 0, false, false));

        StorageCleanupResultDto result = service.thinArchives(TENANT, null, 5, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getAction()).isEqualTo("archives");
        assertThat(result.getKeepLatest()).isEqualTo(5);
        assertThat(result.getDeletedEntries()).isEqualTo(2);
        assertThat(result.getFreedBytes()).isEqualTo(200L);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(archiveService, times(2)).deleteArchive(captor.capture());
        assertThat(captor.getAllValues()).containsExactly("a6", "a7");
        verify(quotaService, times(1)).invalidateAllCaches(TENANT);
    }

    @Test
    void thinArchives_missingKeepLatest_usesTheDefaultNeverZero() {
        // A request without the field (empty body, misspelled key) must not
        // turn into "keep nothing" — that wiped the whole history before.
        when(archiveService.findBeyondKeepLatest(anyString(), any(), anyInt())).thenReturn(List.of());
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 1L, 0, false, false));

        StorageCleanupResultDto result = service.thinArchives(TENANT, null, null, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getKeepLatest()).isEqualTo(StorageCleanupService.DEFAULT_KEEP_LATEST);
        verify(archiveService).findBeyondKeepLatest(TENANT, null, StorageCleanupService.DEFAULT_KEEP_LATEST);
    }

    @Test
    void thinArchives_explicitZero_keepsNothing() {
        when(archiveService.findBeyondKeepLatest(TENANT, null, 0))
                .thenReturn(List.of(new DocumentArchiveService.ArchiveSize("a1", 10L)));
        when(quotaService.status(TENANT)).thenReturn(new StorageQuotaService.Status(TENANT, 0L, 1L, 0, false, false));

        StorageCleanupResultDto result = service.thinArchives(TENANT, null, 0, testIdentity(), WriteActor.SYSTEM);

        assertThat(result.getKeepLatest()).isZero();
        assertThat(result.getDeletedEntries()).isEqualTo(1);
    }

    @Test
    void clampKeepLatest_nullNegativeAndHuge() {
        assertThat(StorageCleanupService.clampKeepLatest(null)).isEqualTo(StorageCleanupService.DEFAULT_KEEP_LATEST);
        assertThat(StorageCleanupService.clampKeepLatest(-1)).isEqualTo(StorageCleanupService.DEFAULT_KEEP_LATEST);
        assertThat(StorageCleanupService.clampKeepLatest(0)).isZero();
        assertThat(StorageCleanupService.clampKeepLatest(7)).isEqualTo(7);
        assertThat(StorageCleanupService.clampKeepLatest(99_999)).isEqualTo(StorageCleanupService.MAX_KEEP_LATEST);
    }

    // ────── fixtures ──────

    private static DocumentService.WriterIdentity testIdentity() {
        return new DocumentService.WriterIdentity("editor-cleanup-test", "u-1", "Cleanup Tester");
    }

    private static DocumentDocument trashDoc(String id, String lineage, long size) {
        return DocumentDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .lineageId(lineage)
                .path(DocumentService.TRASH_FOLDER_PREFIX + id + "_name.yaml")
                .name("name.yaml")
                .size(size)
                .status(DocumentStatus.ACTIVE)
                .build();
    }
}
