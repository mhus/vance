package de.mhus.vance.brain.discovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.brain.skill.SkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cache coherence for the discovery catalog. Before this listener the
 * snapshot survived until the next brain restart, so a freshly written
 * manual stayed invisible to {@code how_do_i} — the invalidation hooks
 * existed but nobody called them.
 */
class SourceCatalogDocumentListenerTest {

    private SourceCatalogService catalogService;
    private SourceCatalogDocumentListener listener;

    @BeforeEach
    void setUp() {
        catalogService = mock(SourceCatalogService.class);
        listener = new SourceCatalogDocumentListener(catalogService);
    }

    private static RoutedDocumentChangedEvent upserted(String path) {
        return new RoutedDocumentChangedEvent.Upserted("acme", "_tenant", path, "id-1");
    }

    @Test
    void manualChange_invalidatesTheTenant() {
        listener.onRoutedDocumentChanged(
                upserted(SourceCatalogBuilder.MANUALS_PREFIX + "browser-automation.md"));

        verify(catalogService).invalidate("acme");
    }

    @Test
    void skillChange_invalidatesTheTenant() {
        listener.onRoutedDocumentChanged(
                upserted(SkillLoader.SKILL_PATH_PREFIX + "research/skill.yaml"));

        verify(catalogService).invalidate("acme");
    }

    @Test
    void deletedManual_invalidatesToo() {
        // Both hard- and soft-delete publish against the original path,
        // so a removed manual must not linger in the catalog either.
        listener.onRoutedDocumentChanged(new RoutedDocumentChangedEvent.Deleted(
                "acme", "_tenant", SourceCatalogBuilder.MANUALS_PREFIX + "gone.md", "id-1"));

        verify(catalogService).invalidate("acme");
    }

    @Test
    void unrelatedDocument_isIgnored() {
        // The catalog reads two prefixes; every other write in the
        // tenant would otherwise throw the cache away for nothing.
        listener.onRoutedDocumentChanged(upserted("documents/notes.md"));

        verify(catalogService, never()).invalidate(any());
    }

    @Test
    void invalidateFailure_isSwallowed() {
        doThrow(new IllegalStateException("boom")).when(catalogService).invalidate(any());

        listener.onRoutedDocumentChanged(
                upserted(SourceCatalogBuilder.MANUALS_PREFIX + "x.md"));
        // No exception escapes — a document write must not fail because
        // a cache could not be dropped.
    }
}
