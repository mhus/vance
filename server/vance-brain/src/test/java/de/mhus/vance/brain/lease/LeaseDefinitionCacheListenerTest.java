package de.mhus.vance.brain.lease;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.lease.LeaseDefinitionLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cache invalidation of lease definitions: only writes to
 * {@code _vance/config/lease/<name>.yaml} drop the named definition, for
 * upserts and deletes alike; a failing invalidation never escapes the
 * event chain.
 */
class LeaseDefinitionCacheListenerTest {

    private LeaseDefinitionLoader loader;
    private LeaseDefinitionCacheListener listener;

    @BeforeEach
    void setUp() {
        loader = mock(LeaseDefinitionLoader.class);
        listener = new LeaseDefinitionCacheListener(loader);
    }

    @Test
    void leaseConfigWrite_invalidatesTheNamedDefinition() {
        listener.onDocumentChanged(
                new RoutedDocumentChangedEvent.Upserted("acme", "_tenant", "_vance/config/lease/tex.yaml", "d-1"));

        verify(loader).invalidate("acme", "_tenant", "tex");
    }

    @Test
    void leaseConfigDelete_invalidatesToo() {
        listener.onDocumentChanged(
                new RoutedDocumentChangedEvent.Deleted("acme", "proj", "_vance/config/lease/browser.yaml", null));

        verify(loader).invalidate("acme", "proj", "browser");
    }

    @Test
    void otherDocuments_areIgnored() {
        listener.onDocumentChanged(
                new RoutedDocumentChangedEvent.Upserted("acme", "proj", "_vance/config/other/tex.yaml", "d-1"));
        listener.onDocumentChanged(
                new RoutedDocumentChangedEvent.Upserted("acme", "proj", "_vance/config/lease/tex.md", "d-2"));

        verify(loader, never()).invalidate(any(), any(), any());
    }

    @Test
    void failingInvalidation_isContained() {
        doThrow(new IllegalStateException("boom")).when(loader).invalidate(any(), any(), any());

        listener.onDocumentChanged(
                new RoutedDocumentChangedEvent.Upserted("acme", "proj", "_vance/config/lease/tex.yaml", "d-1"));
    }
}
