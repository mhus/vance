package de.mhus.vance.brain.documents.events;

import org.jspecify.annotations.Nullable;

/**
 * Post-routing document-change event — the type that the actual cache-coherence
 * listeners (ServerToolRegistry, UrsaScheduler, UrsaHook, …) subscribe to.
 *
 * <p>Structural twin of {@link de.mhus.vance.shared.document.DocumentChangedEvent}
 * with a separate type so the
 * {@link DocumentChangeRouter} can listen to the <em>raw</em> event from
 * {@code DocumentService} without re-entering on its own publish. Listener
 * contract — same five rules as the raw event:
 *
 * <ol>
 *   <li>Idempotent.</li>
 *   <li>Write-free (no callbacks into {@code DocumentService}).</li>
 *   <li>Listener swallows its own {@code RuntimeException}s.</li>
 *   <li>Path-prefix filter first — cheap on every fire.</li>
 *   <li>No user-scoped mutations — refresh is read-only against Mongo.</li>
 * </ol>
 *
 * <p>See {@code planning/document-change-events.md}.
 */
public sealed interface RoutedDocumentChangedEvent
        permits RoutedDocumentChangedEvent.Upserted, RoutedDocumentChangedEvent.Deleted {

    String tenantId();
    String projectId();
    String path();
    @Nullable String documentId();

    record Upserted(
            String tenantId,
            String projectId,
            String path,
            String documentId)
            implements RoutedDocumentChangedEvent {}

    record Deleted(
            String tenantId,
            String projectId,
            String path,
            @Nullable String documentId)
            implements RoutedDocumentChangedEvent {}
}
