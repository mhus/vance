import { type ComputedRef, type Ref } from 'vue';
import { BrainWebSocket, type BrainWsApi } from '@vance/shared';
import type { DocumentViewer } from '@vance/generated';
export type WsStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'down';
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
//# sourceMappingURL=wsConnectionStore.d.ts.map