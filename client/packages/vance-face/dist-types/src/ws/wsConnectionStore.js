import { computed, reactive, ref, watch } from 'vue';
import { BrainWebSocket, getTenantId, setActiveSessionId, } from '@vance/shared';
/**
 * Per-Browser-Tab WebSocket-Connection singleton.
 *
 * <p>Hält genau **eine** {@link BrainWebSocket} pro HTML-Entry und genau
 * **eine** an sie gebundene Session. Komponenten (CortexChatPanel,
 * ChatApp, …) öffnen/schließen nicht mehr selbst — sie melden mit
 * {@link bindSession} ihre gewünschte Session an, beim Verlassen mit
 * {@link leaveChat}. Wechsel zwischen Sessions wird intern als
 * {@code session-unbind} → {@code session-resume} abgewickelt.
 *
 * <p>Reconnect-Verhalten:
 * <ul>
 *   <li>Bei {@code socket.onClose} startet ein exponentieller
 *       Backoff-Loop (1s → 2s → 4s → … → 30s cap), {@code status} wird
 *       {@code 'reconnecting'}.</li>
 *   <li>Nach {@link MAX_RECONNECT_ATTEMPTS} Versuchen → {@code 'down'},
 *       der {@code &lt;ReconnectOverlay&gt;} zeigt den manuellen Button.</li>
 *   <li>Browser-Resume (visibilitychange → visible, online-Event)
 *       triggert sofortigen Reconnect-Versuch und Reset des Backoffs.</li>
 *   <li>Nach erfolgreichem Reconnect wird die gemerkte
 *       {@code desiredSessionId} automatisch re-resumed.</li>
 * </ul>
 *
 * <p>Realisiert als Modul-Level Reactive-Singleton (nicht Pinia) — dem
 * gleichen Muster folgend wie {@code notificationStore.ts}: nicht alle
 * MPA-Entries registrieren Pinia, und die Connection muss überall
 * verfügbar sein, ohne dass jedes {@code main.ts} angefasst werden muss.
 *
 * <p>Siehe {@code planning/live-ws.md} und das Folge-Konzept zur
 * Tab-persistenten Connection-Verwaltung.
 */
const CLIENT_VERSION = '0.1.0';
const PROFILE = 'web';
const MAX_RECONNECT_ATTEMPTS = 8;
const RECONNECT_BASE_DELAY_MS = 1000;
const RECONNECT_MAX_DELAY_MS = 30_000;
const SESSION_LEAVE_GRACE_MS = 10_000;
const socket = ref(null);
const status = ref('idle');
const reconnectAttempts = ref(0);
const lastError = ref(null);
/**
 * Die Session, an die der Client gerade aktiv attached sein **will**.
 * Wird von {@link bindSession} gesetzt. Nach Reconnect wird damit
 * automatisch wieder per {@code session-resume} gebunden.
 *
 * <p>Unterschied zu {@link activeSessionId}: {@code desiredSessionId}
 * ist die "Soll-Lage" aus Client-Sicht, {@code activeSessionId} die
 * "Ist-Lage" nachdem der Server bestätigt hat. Während Reconnect /
 * Bind-Roundtrip können beide kurz divergieren.
 */
const desiredSessionId = ref(null);
const activeSessionId = ref(null);
/**
 * Desired-state of {@code documents}-channel subscriptions. The store is
 * the authority — components register/unregister paths here, the store
 * (re-)sends them over the wire to the server. On reconnect the whole
 * set is re-emitted, which is the primary stale-state recovery
 * mechanism (see planning/document-presence.md §5.4).
 */
const desiredSubscriptions = new Set();
/**
 * Server-pushed viewer roster per path. Reactive map — consumed by the
 * {@code &lt;DocumentPresenceStrip&gt;} component. Server pre-filters the
 * recipient's own editorId out of each list, so the entries are always
 * "other people / other tabs".
 */
const documentViewers = reactive(new Map());
/** Listeners for the documents-channel presence push. */
let documentsUnsubscribe = null;
const documentChangedListeners = new Map();
let releaseTimer = null;
let reconnectTimer = null;
let onCloseUnsubscribe = null;
let visibilityWired = false;
let documentsSocketWatchStopped = false;
// ─── helpers ────────────────────────────────────────────────
function clearReleaseTimer() {
    if (releaseTimer !== null) {
        clearTimeout(releaseTimer);
        releaseTimer = null;
    }
}
function clearReconnectTimer() {
    if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
}
function detachCloseListener() {
    if (onCloseUnsubscribe) {
        try {
            onCloseUnsubscribe();
        }
        catch {
            /* ignore */
        }
        onCloseUnsubscribe = null;
    }
}
function wireBrowserResumeListeners() {
    if (visibilityWired || typeof window === 'undefined')
        return;
    visibilityWired = true;
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState !== 'visible')
            return;
        // Tab wurde wieder sichtbar (iPad-Resume, Tab-Switch, Browser-Wake).
        // Wenn wir down sind oder gerade reconnecten, sofort einen Versuch
        // mit zurückgesetztem Backoff machen.
        if (status.value === 'down' || status.value === 'reconnecting') {
            manualReconnect();
        }
    });
    window.addEventListener('online', () => {
        // Netzwerk ist zurück — falls Socket noch hängt, sofort retry.
        if (status.value === 'down' || status.value === 'reconnecting') {
            manualReconnect();
        }
    });
}
function openRaw(opts) {
    return BrainWebSocket.connect({
        tenant: opts.tenant,
        profile: PROFILE,
        clientVersion: CLIENT_VERSION,
        jwt: opts.jwt,
    });
}
function handleSocketClose() {
    detachCloseListener();
    socket.value = null;
    activeSessionId.value = null;
    setActiveSessionId(null);
    // Wenn wir aktiv die Verbindung weghaben wollten → status='idle' bleibt
    // (manualClose hat ihn schon gesetzt). Ansonsten: reconnect-Loop.
    if (status.value === 'idle')
        return;
    status.value = 'reconnecting';
    scheduleReconnect();
}
function scheduleReconnect() {
    clearReconnectTimer();
    if (reconnectAttempts.value >= MAX_RECONNECT_ATTEMPTS) {
        status.value = 'down';
        return;
    }
    const delay = Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * 2 ** reconnectAttempts.value);
    reconnectTimer = setTimeout(attemptReconnect, delay);
}
async function attemptReconnect() {
    reconnectTimer = null;
    reconnectAttempts.value += 1;
    const tenant = getTenantId();
    if (!tenant) {
        lastError.value = 'No tenant id';
        status.value = 'down';
        return;
    }
    try {
        const fresh = await openRaw({ tenant });
        socket.value = fresh;
        status.value = 'connected';
        reconnectAttempts.value = 0;
        lastError.value = null;
        onCloseUnsubscribe = fresh.onClose(handleSocketClose);
        // Auto-resume desired session, if the editor had one before the drop.
        if (desiredSessionId.value) {
            try {
                await fresh.send('session-resume', { sessionId: desiredSessionId.value });
                activeSessionId.value = desiredSessionId.value;
                setActiveSessionId(desiredSessionId.value);
            }
            catch (resumeErr) {
                // Resume failed — session might no longer exist, or another
                // client took the lease. Leave activeSessionId null; the editor
                // will surface the failure via its own bind-call when it
                // notices.
                console.warn('[wsStore] auto-resume after reconnect failed:', resumeErr);
            }
        }
    }
    catch (e) {
        lastError.value = e instanceof Error ? e.message : String(e);
        scheduleReconnect();
    }
}
async function doSendUnbind() {
    if (socket.value && !socket.value.closed()) {
        try {
            socket.value.sendNoReply('session-unbind');
        }
        catch (e) {
            console.warn('[wsStore] session-unbind send failed:', e);
        }
    }
    activeSessionId.value = null;
    setActiveSessionId(null);
}
async function doSendResume(sessionId) {
    if (!socket.value) {
        throw new Error('WebSocket not connected');
    }
    await socket.value.send('session-resume', { sessionId });
    activeSessionId.value = sessionId;
    setActiveSessionId(sessionId);
}
// ─── public surface ─────────────────────────────────────────
/**
 * Ensure the tab-singleton WebSocket is open. Idempotent — returns the
 * existing socket if already connected, otherwise opens one. Throws on
 * connect failure; the caller surfaces the error and the auto-reconnect
 * loop (if applicable) takes over from there.
 */
export async function ensureConnected(opts) {
    wireBrowserResumeListeners();
    if (socket.value && !socket.value.closed()) {
        return socket.value;
    }
    const tenant = getTenantId();
    if (!tenant) {
        throw new Error('Missing tenant — cannot open chat connection.');
    }
    status.value = 'connecting';
    lastError.value = null;
    try {
        const fresh = await openRaw({ tenant, jwt: opts?.jwt });
        socket.value = fresh;
        status.value = 'connected';
        reconnectAttempts.value = 0;
        onCloseUnsubscribe = fresh.onClose(handleSocketClose);
        return fresh;
    }
    catch (e) {
        lastError.value = e instanceof Error ? e.message : String(e);
        status.value = 'down';
        throw e;
    }
}
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
export async function bindSession(sessionId) {
    clearReleaseTimer();
    desiredSessionId.value = sessionId;
    const sock = await ensureConnected();
    if (activeSessionId.value === sessionId) {
        return sock;
    }
    if (activeSessionId.value !== null) {
        await doSendUnbind();
    }
    await doSendResume(sessionId);
    return sock;
}
/**
 * The editor is unmounting / the user is leaving the chat-bearing view.
 * Start the grace timer; if no {@link bindSession} call comes in within
 * {@code SESSION_LEAVE_GRACE_MS}, the session is released
 * server-side via {@code session-unbind}. The WebSocket itself stays
 * open — it belongs to the tab, not the editor.
 */
export function leaveChat() {
    if (desiredSessionId.value === null)
        return;
    desiredSessionId.value = null;
    clearReleaseTimer();
    releaseTimer = setTimeout(() => {
        releaseTimer = null;
        void doSendUnbind();
    }, SESSION_LEAVE_GRACE_MS);
}
// ─── documents-channel ──────────────────────────────────────
/**
 * Subscribe to presence for {@code path}. Adds to the desired-set so
 * Reconnect-Resubscribe replays it after a socket-swap. Re-sub on the
 * same path is idempotent on the wire but cheap.
 */
export async function subscribeDocument(path) {
    desiredSubscriptions.add(path);
    const sock = await ensureConnected();
    sock.sendOnChannel('documents', 'subscribe', { path });
}
/**
 * Drop a documents-channel subscription. Removes from desired-set so it
 * stays gone after Reconnect-Resubscribe.
 */
export async function unsubscribeDocument(path) {
    desiredSubscriptions.delete(path);
    documentViewers.delete(path);
    if (socket.value && !socket.value.closed()) {
        socket.value.sendOnChannel('documents', 'unsubscribe', { path });
    }
}
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
export function onDocumentChanged(path, handler) {
    let set = documentChangedListeners.get(path);
    if (!set) {
        set = new Set();
        documentChangedListeners.set(path, set);
    }
    set.add(handler);
    return () => {
        const current = documentChangedListeners.get(path);
        if (!current)
            return;
        current.delete(handler);
        if (current.size === 0)
            documentChangedListeners.delete(path);
    };
}
function attachDocumentsListener(sock) {
    detachDocumentsListener();
    const presenceOff = sock.onChannel('documents', 'presence', (data) => {
        if (!data || !data.path)
            return;
        documentViewers.set(data.path, data.viewers ?? []);
    });
    const changedOff = sock.onChannel('documents', 'changed', (data) => {
        if (!data || !data.path)
            return;
        const listeners = documentChangedListeners.get(data.path);
        if (!listeners || listeners.size === 0)
            return;
        for (const handler of Array.from(listeners)) {
            try {
                handler(data);
            }
            catch (e) {
                console.warn(`[wsStore] document-changed handler for '${data.path}' threw:`, e);
            }
        }
    });
    documentsUnsubscribe = () => {
        try {
            presenceOff();
        }
        catch { /* ignore */ }
        try {
            changedOff();
        }
        catch { /* ignore */ }
    };
}
function detachDocumentsListener() {
    if (documentsUnsubscribe) {
        try {
            documentsUnsubscribe();
        }
        catch { /* ignore */ }
        documentsUnsubscribe = null;
    }
}
function wireDocumentsSocketWatch() {
    if (documentsSocketWatchStopped)
        return;
    documentsSocketWatchStopped = true;
    watch(socket, (next, prev) => {
        if (prev) {
            // Old socket gone — its server-side subscriptions are dropped by
            // the WS-close hook. Clear the cached viewers map so we don't show
            // stale rosters until the new sub-acks come in.
            documentViewers.clear();
        }
        detachDocumentsListener();
        if (next) {
            attachDocumentsListener(next);
            // Re-emit desired-set on every fresh socket. Same-as-old or
            // brand-new — server treats subscribe as idempotent. This is the
            // primary stale-state recovery mechanism.
            for (const path of desiredSubscriptions) {
                try {
                    next.sendOnChannel('documents', 'subscribe', { path });
                }
                catch (e) {
                    console.warn(`[wsStore] reconnect-resubscribe failed for path='${path}':`, e);
                }
            }
        }
    }, { immediate: true });
}
// Wire the documents socket-watch lazily on first store use — keeps the
// module-import side-effect-free for tests / SSR.
wireDocumentsSocketWatch();
/**
 * Inform the store that the server-side binding is already in place
 * (e.g. after a {@code session-bootstrap} that creates + binds in one
 * roundtrip). Updates the store state without sending another frame.
 */
export function markBound(sessionId) {
    clearReleaseTimer();
    desiredSessionId.value = sessionId;
    activeSessionId.value = sessionId;
    setActiveSessionId(sessionId);
}
/**
 * Cancel any pending leave-grace timer and release immediately. Used
 * when the user explicitly wants to drop the session (logout,
 * picker-mode in chat.html).
 */
export async function unbindNow() {
    clearReleaseTimer();
    desiredSessionId.value = null;
    await doSendUnbind();
}
/**
 * Manual reconnect entry-point — called by the
 * {@code &lt;ReconnectOverlay&gt;} button after the auto-loop gave up,
 * and by the browser-resume handlers. Resets the backoff and starts a
 * fresh attempt.
 */
export function manualReconnect() {
    clearReconnectTimer();
    reconnectAttempts.value = 0;
    status.value = 'reconnecting';
    void attemptReconnect();
}
/**
 * Hard close the tab-singleton. Stops the reconnect loop and abandons
 * the bound session. Mostly used by the host on app teardown / logout
 * — most editors should call {@link leaveChat} instead.
 */
export function closeConnection() {
    clearReconnectTimer();
    clearReleaseTimer();
    desiredSessionId.value = null;
    activeSessionId.value = null;
    setActiveSessionId(null);
    status.value = 'idle';
    detachCloseListener();
    if (socket.value && !socket.value.closed()) {
        socket.value.close();
    }
    socket.value = null;
}
/**
 * Read-only view of the singleton's reactive state.
 */
export function useWsConnection() {
    return {
        socket,
        status,
        activeSessionId,
        desiredSessionId,
        reconnectAttempts,
        maxReconnectAttempts: MAX_RECONNECT_ATTEMPTS,
        lastError,
        isOverlayVisible: computed(() => status.value === 'reconnecting' || status.value === 'down'),
        documentViewers,
    };
}
//# sourceMappingURL=wsConnectionStore.js.map