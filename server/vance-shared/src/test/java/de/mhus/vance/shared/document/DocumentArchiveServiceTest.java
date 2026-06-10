package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.storage.StorageService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link DocumentArchiveService} — archive-on-write moves the
 * storage pointer (no copy), restore duplicates the blob, delete drops the
 * exclusively owned blob, and lineage-wide wipe takes everything with it.
 */
class DocumentArchiveServiceTest {

    private DocumentArchiveRepository repository;
    private StorageService storageService;
    private DocumentArchiveService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentArchiveRepository.class);
        storageService = mock(StorageService.class);
        service = new DocumentArchiveService(repository, storageService);
        when(repository.save(any(DocumentArchiveDocument.class)))
                .thenAnswer(inv -> {
                    DocumentArchiveDocument a = inv.getArgument(0);
                    if (a.getId() == null) a.setId("arc-" + System.nanoTime());
                    return a;
                });
    }

    // ──── archiveCurrent ───────────────────────────────────────────────

    @Test
    void archiveCurrent_storageBacked_movesPointerAndClearsLiveStorageId() {
        DocumentDocument doc = baseDoc()
                .storageId("blob-1")
                .size(1024)
                .build();
        doc.setId("doc-1");
        doc.setLineageId("lin-1");

        DocumentArchiveDocument saved = service.archiveCurrent(doc);

        // Storage pointer landed on archive entry.
        assertThat(saved.getStorageId()).isEqualTo("blob-1");
        assertThat(saved.getLineageId()).isEqualTo("lin-1");
        assertThat(saved.getOriginalDocumentId()).isEqualTo("doc-1");
        assertThat(saved.getArchivedAt()).isNotNull();

        // Live document's pointer was cleared — caller cannot delete the
        // blob through StorageService now (would orphan the archive).
        assertThat(doc.getStorageId()).isNull();

        // No copy operation happened.
        verify(storageService, never()).duplicate(anyString(), anyString());
    }

    @Test
    void archiveCurrent_rejectsTransientDocument() {
        DocumentDocument doc = baseDoc().storageId("blob-x").build();
        doc.setLineageId("lin-x");
        // id stays null

        assertThatThrownBy(() -> service.archiveCurrent(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transient");
    }

    @Test
    void archiveCurrent_rejectsDocumentWithoutLineage() {
        DocumentDocument doc = baseDoc().storageId("blob-x").build();
        doc.setId("doc-3");
        doc.setLineageId("");

        assertThatThrownBy(() -> service.archiveCurrent(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineageId");
    }

    // ──── restore ───────────────────────────────────────────────────────

    @Test
    void restore_storageBackedArchive_duplicatesBlob() {
        DocumentArchiveDocument archive = DocumentArchiveDocument.builder()
                .id("arc-2")
                .lineageId("lin-2")
                .tenantId("t1")
                .projectId("p1")
                .path("attach/x.pdf")
                .name("x.pdf")
                .mimeType("application/pdf")
                .storageId("blob-old")
                .size(5000)
                .build();
        when(storageService.duplicate("blob-old", "t1")).thenReturn("blob-new");

        DocumentArchiveService.RestorePayload payload = service.restore(archive);

        assertThat(payload.storageId()).isEqualTo("blob-new");
        // Archive untouched — same id still on archive entry.
        assertThat(archive.getStorageId()).isEqualTo("blob-old");
    }

    @Test
    void restore_storageDuplicateFailure_propagates() {
        DocumentArchiveDocument archive = DocumentArchiveDocument.builder()
                .id("arc-3").lineageId("lin-3").tenantId("t1").projectId("p1")
                .path("x").name("x").storageId("blob-z").size(1).build();
        when(storageService.duplicate("blob-z", "t1")).thenReturn(null);

        assertThatThrownBy(() -> service.restore(archive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    // ──── deleteArchive / deleteAllForLineage ──────────────────────────

    @Test
    void deleteArchive_removesRowAndBlob() {
        DocumentArchiveDocument archive = DocumentArchiveDocument.builder()
                .id("arc-9").lineageId("lin-9").tenantId("t1").projectId("p1")
                .path("x").name("x").storageId("blob-9").size(1).build();
        when(repository.findById("arc-9")).thenReturn(Optional.of(archive));

        service.deleteArchive("arc-9");

        verify(storageService).delete("blob-9");
        verify(repository).delete(archive);
    }

    @Test
    void deleteArchive_unknownId_isNoop() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        service.deleteArchive("missing");

        verify(storageService, never()).delete(anyString());
        verify(repository, never()).delete(any(DocumentArchiveDocument.class));
    }

    @Test
    void deleteAllForLineage_dropsEachBlobAndAllRows() {
        DocumentArchiveDocument a1 = DocumentArchiveDocument.builder()
                .id("arc-1").lineageId("lin-x").tenantId("t1").projectId("p1")
                .storageId("blob-1").build();
        DocumentArchiveDocument a2 = DocumentArchiveDocument.builder()
                .id("arc-2").lineageId("lin-x").tenantId("t1").projectId("p1")
                .storageId("blob-2").build();
        DocumentArchiveDocument a3 = DocumentArchiveDocument.builder()
                .id("arc-3").lineageId("lin-x").tenantId("t1").projectId("p1")
                .storageId("blob-3").build();
        when(repository.findByTenantIdAndProjectIdAndLineageIdOrderByArchivedAtDesc("t1", "p1", "lin-x"))
                .thenReturn(List.of(a1, a2, a3));
        when(repository.deleteByTenantIdAndProjectIdAndLineageId("t1", "p1", "lin-x"))
                .thenReturn(3L);

        long deleted = service.deleteAllForLineage("t1", "p1", "lin-x");

        assertThat(deleted).isEqualTo(3);
        verify(storageService).delete("blob-1");
        verify(storageService).delete("blob-2");
        verify(storageService).delete("blob-3");
        verify(storageService, times(3)).delete(anyString());
        verify(repository).deleteByTenantIdAndProjectIdAndLineageId("t1", "p1", "lin-x");
    }

    @Test
    void deleteAllForLineage_blankLineage_isNoop() {
        assertThat(service.deleteAllForLineage("t1", "p1", "")).isZero();
        verify(repository, never()).deleteByTenantIdAndProjectIdAndLineageId(any(), any(), any());
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static DocumentDocument.DocumentDocumentBuilder baseDoc() {
        return DocumentDocument.builder()
                .tenantId("t1")
                .projectId("p1")
                .path("notes/a.md")
                .name("a.md")
                .title("Title")
                .tags(new java.util.ArrayList<>(List.of("draft")))
                .mimeType("text/markdown")
                .createdBy("alice");
    }
}
