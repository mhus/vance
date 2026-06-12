package de.mhus.vance.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentArchiveService;
import de.mhus.vance.shared.document.DocumentArchiveService.ArchiveOrphanCandidate;
import de.mhus.vance.shared.document.DocumentService;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link StorageOrphanCleanupService} against in-memory fakes for
 * the three collaborators. No Mongo required — the cursor callbacks are
 * invoked by the mocks with the batches the test wants to feed in.
 */
class StorageOrphanCleanupServiceTest {

    private DocumentService documentService;
    private DocumentArchiveService archiveService;
    private StorageService storageService;
    private StorageOrphanCleanupService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        archiveService = mock(DocumentArchiveService.class);
        storageService = mock(StorageService.class);
        service = new StorageOrphanCleanupService(documentService, archiveService, storageService);
    }

    @Test
    void sweepOnce_callsBothPhasesAndAggregatesCounts() {
        // Archive batch: a1 → lineage "L1" (live), a2 → lineage "L2" (gone).
        feedArchiveBatch(new ArchiveOrphanCandidate("a1", "L1"),
                new ArchiveOrphanCandidate("a2", "L2"));
        when(documentService.findLineageIdsWithLiveDocument(any()))
                .thenReturn(Set.of("L1"));

        // Storage batch: s1 referenced by doc, s2 by archive, s3 orphan.
        feedStorageBatch("s1", "s2", "s3");
        when(documentService.findReferencedStorageIds(any())).thenReturn(Set.of("s1"));
        when(archiveService.findReferencedStorageIds(any())).thenReturn(Set.of("s2"));

        StorageOrphanCleanupService.CleanupResult result =
                service.sweepOnce(Instant.parse("2026-06-12T08:00:00Z"),
                        Duration.ofHours(1), 100);

        assertThat(result.orphanArchivesDeleted()).isEqualTo(1);
        assertThat(result.orphanStorageDeleted()).isEqualTo(1);
        verify(archiveService).deleteArchive("a2");
        verify(archiveService, never()).deleteArchive("a1");
        verify(storageService).delete("s3");
        verify(storageService, never()).delete("s1");
        verify(storageService, never()).delete("s2");
    }

    @Test
    void sweepOnce_skipsArchiveDeleteWhenLineageHasLiveDoc() {
        feedArchiveBatch(new ArchiveOrphanCandidate("a1", "L1"));
        when(documentService.findLineageIdsWithLiveDocument(any()))
                .thenReturn(Set.of("L1"));
        feedStorageBatch(); // empty storage batch

        service.sweepOnce(Instant.now(), Duration.ofHours(1), 100);

        verify(archiveService, never()).deleteArchive(any());
    }

    @Test
    void sweepOnce_archiveWithoutLineageId_isOrphan() {
        // Archive entries that somehow have a blank lineageId — defensive
        // path. Treat as orphan since they can never be re-anchored.
        feedArchiveBatch(new ArchiveOrphanCandidate("a-empty", ""));
        when(documentService.findLineageIdsWithLiveDocument(any())).thenReturn(Set.of());
        feedStorageBatch();

        long n = service.sweepOnce(Instant.now(), Duration.ofHours(1), 100)
                .orphanArchivesDeleted();

        assertThat(n).isEqualTo(1);
        verify(archiveService).deleteArchive("a-empty");
    }

    @Test
    void sweepOnce_archiveDeleteFailure_doesNotAbortBatch() {
        // a1 fails, a2 still gets processed.
        feedArchiveBatch(new ArchiveOrphanCandidate("a1", "L1"),
                new ArchiveOrphanCandidate("a2", "L2"));
        when(documentService.findLineageIdsWithLiveDocument(any())).thenReturn(Set.of());
        doAnswer(inv -> {
            throw new RuntimeException("boom");
        }).when(archiveService).deleteArchive("a1");
        feedStorageBatch();

        long n = service.sweepOnce(Instant.now(), Duration.ofHours(1), 100)
                .orphanArchivesDeleted();

        // Only a2 counts towards deleted — a1 failed.
        assertThat(n).isEqualTo(1);
        verify(archiveService).deleteArchive("a1");
        verify(archiveService).deleteArchive("a2");
    }

    @Test
    void sweepOnce_storageReferencedByBothSides_isNotDeleted() {
        feedArchiveBatch();
        feedStorageBatch("shared");
        when(documentService.findReferencedStorageIds(any())).thenReturn(Set.of("shared"));
        when(archiveService.findReferencedStorageIds(any())).thenReturn(Set.of("shared"));

        long n = service.sweepOnce(Instant.now(), Duration.ofHours(1), 100)
                .orphanStorageDeleted();

        assertThat(n).isZero();
        verify(storageService, never()).delete(any(String.class));
    }

    @Test
    void sweepOnce_cutoffIsNowMinusGracePeriod() {
        feedArchiveBatch();
        feedStorageBatch();
        Instant now = Instant.parse("2026-06-12T08:00:00Z");
        Duration grace = Duration.ofMinutes(90);

        service.sweepOnce(now, grace, 100);

        verify(storageService).forEachFinalStorageIdOlderThan(
                eq(now.minus(grace)), eq(100), any());
    }

    @Test
    void sweepOnce_emptyBatches_returnsZero() {
        feedArchiveBatch();
        feedStorageBatch();

        StorageOrphanCleanupService.CleanupResult r =
                service.sweepOnce(Instant.now(), Duration.ofHours(1), 100);

        assertThat(r.orphanArchivesDeleted()).isZero();
        assertThat(r.orphanStorageDeleted()).isZero();
        assertThat(r.isClean()).isTrue();
    }

    /** Configures {@code archiveService.forEachArchive} to feed a single batch. */
    private void feedArchiveBatch(ArchiveOrphanCandidate... batch) {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<List<ArchiveOrphanCandidate>> handler = inv.getArgument(1);
            if (batch.length > 0) handler.accept(List.of(batch));
            return null;
        }).when(archiveService).forEachArchive(anyInt(), any());
    }

    /** Configures {@code storageService.forEachFinalStorageIdOlderThan} to feed a single batch. */
    private void feedStorageBatch(String... batch) {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<List<String>> handler = inv.getArgument(2);
            if (batch.length > 0) handler.accept(List.of(batch));
            return null;
        }).when(storageService).forEachFinalStorageIdOlderThan(any(), anyInt(), any());
        // Default the reference-lookups to "nothing referenced" so individual
        // tests can override only the side they care about.
        when(documentService.findReferencedStorageIds(any(Collection.class)))
                .thenReturn(Set.of());
        when(archiveService.findReferencedStorageIds(any(Collection.class)))
                .thenReturn(Set.of());
    }
}
