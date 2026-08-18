package de.mhus.vance.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import de.mhus.vance.shared.document.DocumentStorageReferenceSource;
import de.mhus.vance.shared.document.ArchiveStorageReferenceSource;
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
        // The two sources a brain contributes — the behaviour this test
        // covered before they were pluggable.
        service = new StorageOrphanCleanupService(storageService,
                List.of(
                        new DocumentStorageReferenceSource(documentService),
                        new ArchiveStorageReferenceSource(archiveService)));
    }

    @Test
    void sweepOnce_storageReferencedByBothSides_isNotDeleted() {
        feedStorageBatch("shared");
        when(documentService.findReferencedStorageIds(any())).thenReturn(Set.of("shared"));
        when(archiveService.findReferencedStorageIds(any())).thenReturn(Set.of("shared"));

        long n = service.sweepOnce(Instant.now(), Duration.ofHours(1), 100);

        assertThat(n).isZero();
        verify(storageService, never()).delete(any(String.class));
    }

    @Test
    void sweepOnce_cutoffIsNowMinusGracePeriod() {
        feedStorageBatch();
        Instant now = Instant.parse("2026-06-12T08:00:00Z");
        Duration grace = Duration.ofMinutes(90);

        service.sweepOnce(now, grace, 100);

        verify(storageService).forEachFinalStorageIdOlderThan(
                eq(now.minus(grace)), eq(100), any());
    }

    @Test
    void sweepOnce_emptyBatches_returnsZero() {
        feedStorageBatch();

        assertThat(service.sweepOnce(Instant.now(), Duration.ofHours(1), 100)).isZero();
    }

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

    // ── Leitplanken der pluggable Quellen ────────────────────────────

    @Test
    void withoutAnySource_nothingIsDeleted() {
        // An empty registry reads as "nobody references anything", which
        // would delete every blob. It never means that — it means this
        // deployment wired no source. The kit store reusing this storage
        // is exactly that case.
        StorageOrphanCleanupService bare =
                new StorageOrphanCleanupService(storageService, List.of());

        assertThat(bare.checkOrphanStorageBatch(List.of("sid-1", "sid-2"))).isZero();
        verify(storageService, never()).delete(any());
    }

    @Test
    void aSourceThatCannotAnswer_abortsTheSweep() {
        // Skipping it would treat its blobs as unreferenced. A failed
        // sweep costs disk until the next run; a half-blind one costs
        // data that is gone.
        StorageReferenceSource broken = new StorageReferenceSource() {
            @Override
            public Set<String> findReferencedStorageIds(java.util.Collection<String> candidates) {
                throw new IllegalStateException("index unavailable");
            }

            @Override
            public String sourceName() {
                return "broken";
            }
        };
        StorageOrphanCleanupService withBroken = new StorageOrphanCleanupService(storageService,
                List.of(new DocumentStorageReferenceSource(documentService), broken));

        assertThatThrownBy(() -> withBroken.checkOrphanStorageBatch(List.of("sid-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");
        verify(storageService, never()).delete(any());
    }

    @Test
    void aBlobHeldByAnyOneSource_survives() {
        // Sources are additive: the store's releases and the brain's
        // documents both keep their own blobs alive.
        StorageReferenceSource releases = new StorageReferenceSource() {
            @Override
            public Set<String> findReferencedStorageIds(java.util.Collection<String> candidates) {
                return Set.of("sid-release");
            }
        };
        when(documentService.findReferencedStorageIds(any())).thenReturn(Set.of());
        when(archiveService.findReferencedStorageIds(any())).thenReturn(Set.of());
        StorageOrphanCleanupService mixed = new StorageOrphanCleanupService(storageService,
                List.of(new DocumentStorageReferenceSource(documentService), releases));

        assertThat(mixed.checkOrphanStorageBatch(List.of("sid-release", "sid-loose"))).isEqualTo(1);
        verify(storageService).delete("sid-loose");
        verify(storageService, never()).delete("sid-release");
    }
}
