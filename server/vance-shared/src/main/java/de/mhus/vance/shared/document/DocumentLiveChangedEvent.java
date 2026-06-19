package de.mhus.vance.shared.document;

import org.jspecify.annotations.Nullable;

/**
 * Spring {@code ApplicationEvent} fired by {@link DocumentService} on every
 * successful Mongo write — companion to {@link DocumentChangedEvent} with a
 * deliberately wider scope.
 *
 * <p><b>Why a separate event:</b> {@link DocumentChangedEvent} is the
 * cache-coherence bus for brain-internal YAML caches; its publish filter is
 * narrow ({@code _vance/...} minus {@code _vance/logs/...}) so listeners
 * are cheap. The live-WS broadcast to {@code documents}-channel subscribers
 * needs to fire for any path a user might have open, including user
 * documents under {@code documents/...} that the narrow event never reaches.
 *
 * <p><b>Publish filter:</b> everything except known noise prefixes — logs,
 * trash, Slartibartfast scratch and chat attachments. The receiving
 * broadcaster only puts a frame on the wire when at least one WebSocket
 * subscriber on the local pod is listening to the path, so extra publishes
 * are cheap.
 *
 * <p><b>editorId</b> is the identity of the WebSocket-connection that
 * authored the write — propagated from the REST {@code X-Editor-Id} header
 * via {@code DocumentController}. The broadcaster uses it to skip the
 * writer's own connection during local fan-out, so the tab that just saved
 * doesn't see its own "extern geändert" banner. {@code null} when the
 * write originates outside the live-WS surface (Slartibartfast generators,
 * scheduler ticks, scripts) — broadcaster then fans out to everyone.
 *
 * <p>Listeners must follow the same contract as for
 * {@link DocumentChangedEvent}: idempotent, write-free, swallow their own
 * {@code RuntimeException}s, filter by prefix early.
 */
public record DocumentLiveChangedEvent(
        String tenantId,
        String projectId,
        String path,
        Kind kind,
        @Nullable String editorId) {

    public enum Kind {
        UPSERTED,
        DELETED
    }
}
