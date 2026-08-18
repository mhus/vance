package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentArchiveService.ArchiveOrphanCandidate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Archives whose lineage is gone.
 *
 * <p>Split out of the blob sweep when that became source-driven: this is a
 * statement about documents and belongs where documents live. The cases
 * are the ones the combined sweep covered before.
 */
class OrphanArchiveCleanupServiceTest {

    private DocumentService documentService;
    private DocumentArchiveService archiveService;
    private OrphanArchiveCleanupService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        archiveService = mock(DocumentArchiveService.class);
        service = new OrphanArchiveCleanupService(documentService, archiveService);
    }

    @Test
    void anArchiveWhoseLineageStillLives_isKept() {
        feed(new ArchiveOrphanCandidate("a1", "L1"), new ArchiveOrphanCandidate("a2", "L2"));
        when(documentService.findLineageIdsWithLiveDocument(any())).thenReturn(Set.of("L1"));

        assertThat(service.sweepOnce(100)).isEqualTo(1);
        verify(archiveService).deleteArchive("a2");
        verify(archiveService, never()).deleteArchive("a1");
    }

    @Test
    void anArchiveWithoutALineage_isOrphan() {
        // Nothing can ever claim it again, so nothing ever will.
        feed(new ArchiveOrphanCandidate("a1", null));
        when(documentService.findLineageIdsWithLiveDocument(any())).thenReturn(Set.of());

        assertThat(service.sweepOnce(100)).isEqualTo(1);
        verify(archiveService).deleteArchive("a1");
    }

    @Test
    void oneFailedDelete_doesNotStopTheBatch() {
        // The next entry is a separate decision; aborting would leave the
        // rest of a batch unswept because of one bad row.
        feed(new ArchiveOrphanCandidate("a1", "gone"), new ArchiveOrphanCandidate("a2", "gone"));
        when(documentService.findLineageIdsWithLiveDocument(any())).thenReturn(Set.of());
        doThrow(new IllegalStateException("nope")).when(archiveService).deleteArchive("a1");

        assertThat(service.sweepOnce(100)).isEqualTo(1);
        verify(archiveService).deleteArchive("a2");
    }

    @SuppressWarnings("unchecked")
    private void feed(ArchiveOrphanCandidate... batch) {
        doAnswer(inv -> {
            ((Consumer<List<ArchiveOrphanCandidate>>) inv.getArgument(1)).accept(List.of(batch));
            return null;
        }).when(archiveService).forEachArchive(any(Integer.class), any());
    }
}
