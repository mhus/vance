package de.mhus.vance.shared.document;

import org.jspecify.annotations.Nullable;

/**
 * Spring {@code ApplicationEvent} fired by {@link DocumentService} after
 * a successful note CRUD (add / update / delete). Carries enough state
 * to update a remote subscriber's local note map without a re-fetch.
 *
 * <p>Live-update model is intentionally simple: last-write-wins, no
 * conflict detection. The receiving client applies the change verbatim;
 * if it had a different value, the new event overwrites it.
 *
 * <p>{@link #editorId} is the writing connection's identity (from the
 * REST {@code X-Editor-Id} header). The broadcaster uses it to skip the
 * writer's own WebSocket during local fan-out — without it the tab that
 * just edited a note would see its own update echo back as if from
 * another user. {@code null} when the write originated outside the
 * live-WS surface (Slartibartfast, scripts, schedulers).
 *
 * <p>For {@link Kind#DELETED}, {@link #note} is {@code null} — the
 * client uses {@link #noteId} alone to drop the local entry.
 */
public record DocumentNotesChangedEvent(
        String tenantId,
        String projectId,
        String path,
        Kind kind,
        String noteId,
        @Nullable DocumentNote note,
        @Nullable String editorId) {

    public enum Kind {
        ADDED,
        UPDATED,
        DELETED
    }
}
