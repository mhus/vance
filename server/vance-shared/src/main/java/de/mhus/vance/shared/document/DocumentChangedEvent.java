package de.mhus.vance.shared.document;

import org.jspecify.annotations.Nullable;

/**
 * Spring {@code ApplicationEvent} fired by {@link DocumentService} right after
 * a successful Mongo write. The event carries only addressing information
 * ({@code tenantId} / {@code projectId} / {@code path} / {@code documentId}) —
 * never payload. Listeners that need the body read it fresh via
 * {@code DocumentService.findByPath}.
 *
 * <p>Two-stage event design: this is the <b>raw</b> event published in
 * {@code DocumentService}. The brain-layer router consumes it, decides
 * which pod(s) should refresh, and re-publishes a separate
 * {@code RoutedDocumentChangedEvent} that the actual cache-coherence
 * listeners (ServerToolRegistry, UrsaScheduler, UrsaHook, …) subscribe to.
 * Keeping the two types apart prevents accidental re-entry on the router.
 *
 * <p>Listener contract — applies to every consumer downstream:
 * <ol>
 *   <li><b>Idempotent</b>. Multiple deliveries must not change the outcome.</li>
 *   <li><b>Write-free</b>. Listeners must not call back into
 *       {@code DocumentService.upsertText} or similar — that would loop.</li>
 *   <li><b>Catches its own {@code RuntimeException}s</b>. Spring's default
 *       behaviour propagates listener exceptions to the publisher; a broken
 *       YAML on disk must not unwind a {@code DocumentService} write.</li>
 *   <li><b>Filter by path prefix early</b> so the listener is cheap on every
 *       fire.</li>
 * </ol>
 *
 * <p>Sealed split: {@link Upserted} for creates and content-replacements,
 * {@link Deleted} for hard-deletes. Move/rename is currently modelled as
 * delete + upsert by {@code DocumentService} and produces two events; if
 * that changes we add a {@code Moved} variant rather than reusing one of
 * the two.
 *
 * @see DocumentService
 */
public sealed interface DocumentChangedEvent
        permits DocumentChangedEvent.Upserted, DocumentChangedEvent.Deleted {

    /** Owning tenant — {@code ProjectDocument.tenantId}. */
    String tenantId();

    /** Owning project — {@code ProjectDocument.name} (system projects: {@code _tenant}, {@code _vance}). */
    String projectId();

    /** Full document path (e.g. {@code _vance/server-tools/zoho_imap.yaml}). */
    String path();

    /** Mongo id of the document, or {@code null} for deletes when the row is already gone. */
    @Nullable String documentId();

    /** Create or content-replacement on an existing document. */
    record Upserted(
            String tenantId,
            String projectId,
            String path,
            String documentId)
            implements DocumentChangedEvent {}

    /** Hard-delete of a document. */
    record Deleted(
            String tenantId,
            String projectId,
            String path,
            @Nullable String documentId)
            implements DocumentChangedEvent {}
}
