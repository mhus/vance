import { type ComputedRef, type Ref } from 'vue';
import { BrainWebSocket, type BrainWsApi } from '@vance/shared';
import type { DocumentChangedNotification, DocumentNoteChangedNotification, DocumentViewer } from '@vance/generated';
export type WsStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'down';
/**
 * Per-path callback registrations for the {@code documents.changed}
 * frame ({@link onDocumentChanged}). Handlers receive the full
 * {@link DocumentChangedNotification} so they can branch on the
 * {@code kind} *and* see who authored the change (for the
 * {@code ⏺ name} awareness badge).
 */
type DocumentChangedHandler = (notification: DocumentChangedNotification) => void;
/**
 * Per-path callbacks for the {@code documents.note-changed} frame —
 * fired when a sticky-note on the path was added / updated / deleted by
 * another connection. Local-write echoes are filtered server-side via
 * the writer's editorId.
 */
type DocumentNoteChangedHandler = (notification: DocumentNoteChangedNotification) => void;
/**
 * Ensure the tab-singleton WebSocket is open. Idempotent — returns the
 * existing socket if already connected, otherwise opens one. Throws on
 * connect failure; the caller surfaces the error and the auto-reconnect
 * loop (if applicable) takes over from there.
 */
export declare function ensureConnected(opts?: {
    jwt?: string;
}): Promise<BrainWebSocket>;
/**
 * Bind to the given session. State machine:
 * <ul>
 *   <li>Same as currently bound → no-op (and any release timer is cancelled).</li>
 *   <li>Different session bound → send {@code session-unbind} first,
 *       then {@code session-resume}.</li>
 *   <li>No session bound → send {@code session-resume} directly.</li>
 * </ul>
 * Caller must have called {@link ensureConnected} first.
 *
 * @returns a {@link BrainWsApi} (the singleton socket) once the bind
 * has been acknowledged by the server.
 */
export declare function bindSession(sessionId: string): Promise<BrainWsApi>;
/**
 * The editor is unmounting / the user is leaving the chat-bearing view.
 * Start the grace timer; if no {@link bindSession} call comes in within
 * {@code SESSION_LEAVE_GRACE_MS}, the session is released
 * server-side via {@code session-unbind}. The WebSocket itself stays
 * open — it belongs to the tab, not the editor.
 */
export declare function leaveChat(): void;
/**
 * Subscribe to presence for {@code path}. Adds to the desired-set so
 * Reconnect-Resubscribe replays it after a socket-swap. Re-sub on the
 * same path is idempotent on the wire but cheap.
 */
export declare function subscribeDocument(path: string): Promise<void>;
/**
 * Drop a documents-channel subscription. Removes from desired-set so it
 * stays gone after Reconnect-Resubscribe.
 */
export declare function unsubscribeDocument(path: string): Promise<void>;
/**
 * Register a callback for the {@code documents.changed} frame of the
 * given path. Returns an unsubscribe function. Editors use this to learn
 * when "their" document was written or deleted on any pod in the
 * cluster — typically called from an editor's {@code onMounted} hook
 * once it knows which path it is showing, paired with the
 * {@link subscribeDocument} call (presence subscribe implies the server
 * fires changed-events to this connection too).
 *
 * <p>Handlers receive the full {@link DocumentChangedNotification}
 * with {@code path}, {@code kind} ({@code "upserted"} / {@code "deleted"})
 * and the writer's identity ({@code editorId} / {@code editorUserId} /
 * {@code editorDisplayName} — useful for the {@code ⏺ name} awareness
 * badge after a silent merge).
 */
export declare function onDocumentChanged(path: string, handler: DocumentChangedHandler): () => void;
/**
 * Register a callback for the {@code documents.note-changed} frame —
 * fires when a sticky-note on the path is added / updated / deleted by
 * another connection. Returns an unsubscribe function. Server-side
 * already filters out the local writer's echo via the X-Editor-Id
 * header, so handlers only see events from *other* connections.
 *
 * <p>Subscribe via {@link subscribeDocument} (presence subscribe implies
 * notes events fire too — same channel, same path-set on the server).
 */
export declare function onDocumentNoteChanged(path: string, handler: DocumentNoteChangedHandler): () => void;
/**
 * Inform the store that the server-side binding is already in place
 * (e.g. after a {@code session-bootstrap} that creates + binds in one
 * roundtrip). Updates the store state without sending another frame.
 */
export declare function markBound(sessionId: string): void;
/**
 * Cancel any pending leave-grace timer and release immediately. Used
 * when the user explicitly wants to drop the session (logout,
 * picker-mode in chat.html).
 */
export declare function unbindNow(): Promise<void>;
/**
 * Manual reconnect entry-point — called by the
 * {@code &lt;ReconnectOverlay&gt;} button after the auto-loop gave up,
 * and by the browser-resume handlers. Resets the backoff and starts a
 * fresh attempt.
 */
export declare function manualReconnect(): void;
/**
 * Hard close the tab-singleton. Stops the reconnect loop and abandons
 * the bound session. Mostly used by the host on app teardown / logout
 * — most editors should call {@link leaveChat} instead.
 */
export declare function closeConnection(): void;
/**
 * Read-only view of the singleton's reactive state.
 */
export declare function useWsConnection(): {
    socket: Ref<BrainWebSocket | null>;
    status: Ref<WsStatus>;
    activeSessionId: Ref<string | null>;
    desiredSessionId: Ref<string | null>;
    reconnectAttempts: Ref<number>;
    maxReconnectAttempts: number;
    lastError: Ref<string | null>;
    isOverlayVisible: ComputedRef<boolean>;
    documentViewers: Map<string, DocumentViewer[]>;
};
export {};
//# sourceMappingURL=wsConnectionStore.d.ts.map