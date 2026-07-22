import { computed, reactive, ref, watch, type ComputedRef, type Ref } from 'vue';
import {
  BrainWebSocket,
  type BrainWsApi,
  getTenantId,
  setActiveSessionId,
} from '@vance/shared';
import type {
  DocumentChangedNotification,
  DocumentNoteChangedNotification,
  DocumentPrefixSubscribeRequest,
  DocumentPresenceNotification,
  DocumentSubscribeRequest,
  DocumentViewer,
  PointerLeaveNotification,
  PointerMoveRequest,
  PointerNotification,
  PointerSubscribeRequest,
  SessionResumeRequest,
  SessionResumeResponse,
  SignalFrame,
  SignalSubscribeRequest,
} from '@vance/generated';

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

export type WsStatus =
  | 'idle' // App-Start oder explizit geschlossen; keine Verbindung
  | 'connecting' // initialer Handshake läuft
  | 'connected' // Socket lebt
  | 'reconnecting' // auto-retry-Loop läuft (Overlay sichtbar)
  | 'down'; // Reconnect-Loop hat aufgegeben (Overlay mit manuellem Button)

const socket: Ref<BrainWebSocket | null> = ref(null);
const status: Ref<WsStatus> = ref('idle');
const reconnectAttempts = ref(0);
const lastError: Ref<string | null> = ref(null);

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
const desiredSessionId: Ref<string | null> = ref(null);
const activeSessionId: Ref<string | null> = ref(null);

/**
 * Desired-state of {@code documents}-channel subscriptions. The store is
 * the authority — components register/unregister paths here, the store
 * (re-)sends them over the wire to the server. On reconnect the whole
 * set is re-emitted, which is the primary stale-state recovery
 * mechanism (see planning/document-presence.md §5.4).
 */
const desiredSubscriptions: Set<string> = new Set();

/**
 * Same as {@link desiredSubscriptions} but for folder-prefix subscriptions
 * (the {@code documents.subscribePrefix} frame, see
 * planning/apps-in-cortex-and-live.md §6). Replayed on every socket-swap
 * so reconnects restore the App-Live-Watch state automatically.
 */
const desiredPrefixSubscriptions: Set<string> = new Set();

/**
 * Server-pushed viewer roster per path. Reactive map — consumed by the
 * {@code &lt;DocumentPresenceStrip&gt;} component. Server pre-filters the
 * recipient's own editorId out of each list, so the entries are always
 * "other people / other tabs".
 */
const documentViewers = reactive(new Map<string, DocumentViewer[]>());

/** Listeners for the documents-channel presence push. */
let documentsUnsubscribe: (() => void) | null = null;

/**
 * Per-path callback registrations for the {@code documents.changed}
 * frame ({@link onDocumentChanged}). Handlers receive the full
 * {@link DocumentChangedNotification} so they can branch on the
 * {@code kind} *and* see who authored the change (for the
 * {@code ⏺ name} awareness badge).
 */
type DocumentChangedHandler = (notification: DocumentChangedNotification) => void;
const documentChangedListeners = new Map<string, Set<DocumentChangedHandler>>();

/**
 * Per-prefix callback registrations for the {@code documents.changed}
 * frame ({@link onDocumentChangedPrefix}). Folder-bound apps subscribe
 * with a single prefix and receive every change whose path starts with
 * it (manifest + sub-documents). The dispatcher walks this map for each
 * incoming frame and invokes every handler whose prefix is a prefix of
 * the changed path.
 */
const documentChangedPrefixListeners = new Map<string, Set<DocumentChangedHandler>>();

/**
 * Per-prefix callback registrations for the synthetic "reconnect" tick
 * ({@link onDocumentPrefixReconnect}). After each successful resubscribe
 * during reconnect-recovery the store emits a notification with
 * {@code kind: "reconnect"} and {@code path = prefix} so app-level
 * consumers can run a force-reload to catch up on writes that happened
 * while the WS was down. Separate map from
 * {@link documentChangedPrefixListeners} so consumers that don't care
 * about reconnect (presence-roster components, single-doc editors with
 * their own ETag-based recovery) don't get woken up.
 */
const documentPrefixReconnectListeners = new Map<string, Set<DocumentChangedHandler>>();

/**
 * Per-path callbacks for the {@code documents.note-changed} frame —
 * fired when a sticky-note on the path was added / updated / deleted by
 * another connection. Local-write echoes are filtered server-side via
 * the writer's editorId.
 */
type DocumentNoteChangedHandler = (notification: DocumentNoteChangedNotification) => void;
const documentNoteChangedListeners = new Map<string, Set<DocumentNoteChangedHandler>>();

/**
 * Desired-state of {@code pointers}-channel subscriptions. Replayed on
 * every socket-swap so reconnects restore live-cursor participation
 * automatically — mirrors {@link desiredSubscriptions}. The pointers
 * channel keeps no roster server-side, so there's no cached viewer map
 * here; stale cursors are cleared client-side by the composable's TTL.
 */
const desiredPointerSubscriptions: Set<string> = new Set();

/** Per-path callbacks for the {@code pointers} channel {@code pointer} frame. */
type PointerHandler = (notification: PointerNotification) => void;
const pointerListeners = new Map<string, Set<PointerHandler>>();

/** Per-path callbacks for the {@code pointers} channel {@code pointer-leave} frame. */
type PointerLeaveHandler = (notification: PointerLeaveNotification) => void;
const pointerLeaveListeners = new Map<string, Set<PointerLeaveHandler>>();

let pointersUnsubscribe: (() => void) | null = null;
let pointersSocketWatchStopped = false;

/** Desired-state of {@code signals}-channel subscriptions (reconnect-replayed). */
const desiredSignalSubscriptions: Set<string> = new Set();

/** Per-path callbacks for the {@code signals} channel {@code signal} frame. */
type SignalHandler = (frame: SignalFrame) => void;
const signalListeners = new Map<string, Set<SignalHandler>>();

let signalsUnsubscribe: (() => void) | null = null;
let signalsSocketWatchStopped = false;

let releaseTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let onCloseUnsubscribe: (() => void) | null = null;
let visibilityWired = false;
let documentsSocketWatchStopped = false;

/**
 * In-flight socket-open promise. Guards against concurrent callers each
 * opening their OWN WebSocket — see {@link connectSingleton}.
 */
let pendingOpen: Promise<BrainWebSocket> | null = null;

// ─── helpers ────────────────────────────────────────────────

function clearReleaseTimer(): void {
  if (releaseTimer !== null) {
    clearTimeout(releaseTimer);
    releaseTimer = null;
  }
}

function clearReconnectTimer(): void {
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
}

function detachCloseListener(): void {
  if (onCloseUnsubscribe) {
    try {
      onCloseUnsubscribe();
    } catch {
      /* ignore */
    }
    onCloseUnsubscribe = null;
  }
}

function wireBrowserResumeListeners(): void {
  if (visibilityWired || typeof window === 'undefined') return;
  visibilityWired = true;
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return;
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

function openRaw(opts: { tenant: string; jwt?: string }): Promise<BrainWebSocket> {
  return BrainWebSocket.connect({
    tenant: opts.tenant,
    profile: PROFILE,
    clientVersion: CLIENT_VERSION,
    jwt: opts.jwt,
  });
}

/**
 * Open the tab-singleton socket, guarded against concurrent callers.
 *
 * <p>Multiple consumers open the connection in parallel on mount — most
 * visibly in cortex.html, where the chat-bind, the {@code documents}
 * live-watch (subscribe / subscribePrefix), the notification subscription
 * and the tool-service attach all call {@link ensureConnected} in the same
 * tick. Without this guard, each caller that observed a {@code null}
 * socket opened its OWN WebSocket; the brain's single-client session
 * registry then kicks all but one, the kicked ones auto-reconnect and kick
 * the survivor, and the tab settles into a ~3s connect/kick ping-pong.
 * Every steer that lands in a kicked connection's brief unbound window
 * earns a 403 "process-steer requires a bound session". chat.html happened
 * to dodge it only because its picker→openAndBind flow opens sequentially.
 *
 * <p>Collapses concurrent opens into ONE socket, and centralises
 * {@code socket.value} assignment + close-listener wiring so both
 * {@link ensureConnected} and {@link attemptReconnect} share one path.
 */
function connectSingleton(opts: { tenant: string; jwt?: string }): Promise<BrainWebSocket> {
  if (socket.value && !socket.value.closed()) {
    return Promise.resolve(socket.value);
  }
  if (pendingOpen) {
    return pendingOpen;
  }
  const p = (async () => {
    try {
      const fresh = await openRaw(opts);
      detachCloseListener();
      socket.value = fresh;
      onCloseUnsubscribe = fresh.onClose(handleSocketClose);
      return fresh;
    } finally {
      pendingOpen = null;
    }
  })();
  pendingOpen = p;
  return p;
}

function handleSocketClose(): void {
  detachCloseListener();
  socket.value = null;
  activeSessionId.value = null;
  setActiveSessionId(null);
  // Wenn wir aktiv die Verbindung weghaben wollten → status='idle' bleibt
  // (manualClose hat ihn schon gesetzt). Ansonsten: reconnect-Loop.
  if (status.value === 'idle') return;
  status.value = 'reconnecting';
  scheduleReconnect();
}

function scheduleReconnect(): void {
  clearReconnectTimer();
  if (reconnectAttempts.value >= MAX_RECONNECT_ATTEMPTS) {
    status.value = 'down';
    return;
  }
  const delay = Math.min(
    RECONNECT_MAX_DELAY_MS,
    RECONNECT_BASE_DELAY_MS * 2 ** reconnectAttempts.value,
  );
  reconnectTimer = setTimeout(attemptReconnect, delay);
}

async function attemptReconnect(): Promise<void> {
  reconnectTimer = null;
  reconnectAttempts.value += 1;
  const tenant = getTenantId();
  if (!tenant) {
    lastError.value = 'No tenant id';
    status.value = 'down';
    return;
  }
  try {
    const fresh = await connectSingleton({ tenant });
    status.value = 'connected';
    reconnectAttempts.value = 0;
    lastError.value = null;
    // Auto-resume desired session, if the editor had one before the drop.
    if (desiredSessionId.value) {
      try {
        await fresh.send<SessionResumeRequest, SessionResumeResponse>(
          'session-resume',
          { sessionId: desiredSessionId.value },
        );
        activeSessionId.value = desiredSessionId.value;
        setActiveSessionId(desiredSessionId.value);
      } catch (resumeErr) {
        // Resume failed — session might no longer exist, or another
        // client took the lease. Leave activeSessionId null; the editor
        // will surface the failure via its own bind-call when it
        // notices.
        console.warn('[wsStore] auto-resume after reconnect failed:', resumeErr);
      }
    }
  } catch (e) {
    lastError.value = e instanceof Error ? e.message : String(e);
    scheduleReconnect();
  }
}

async function doSendUnbind(): Promise<void> {
  if (socket.value && !socket.value.closed()) {
    try {
      socket.value.sendNoReply('session-unbind');
    } catch (e) {
      console.warn('[wsStore] session-unbind send failed:', e);
    }
  }
  activeSessionId.value = null;
  setActiveSessionId(null);
}

async function doSendResume(sessionId: string): Promise<void> {
  if (!socket.value) {
    throw new Error('WebSocket not connected');
  }
  await socket.value.send<SessionResumeRequest, SessionResumeResponse>(
    'session-resume',
    { sessionId },
  );
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
export async function ensureConnected(opts?: { jwt?: string }): Promise<BrainWebSocket> {
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
    const fresh = await connectSingleton({ tenant, jwt: opts?.jwt });
    status.value = 'connected';
    reconnectAttempts.value = 0;
    return fresh;
  } catch (e) {
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
export async function bindSession(sessionId: string): Promise<BrainWsApi> {
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
 * Guarantee the tab-singleton socket is open <em>and</em> the desired
 * session is server-confirmed bound, before the caller sends a
 * session-scoped frame ({@code process-steer}, …).
 *
 * <p>Closes the reconnect race: after a socket drop {@link attemptReconnect}
 * reopens and re-resumes asynchronously, flipping {@code status} to
 * {@code 'connected'} <em>before</em> the {@code session-resume} roundtrip
 * completes — and it swallows a failed auto-resume, leaving the socket up
 * but unbound. A steer sent in either window earns a server 403
 * "process-steer requires a bound session". Checking only "is the socket
 * open?" (the previous composer guard) does not catch this.
 *
 * <p>Idempotent and cheap when already bound; self-heals a previously
 * failed / still-in-flight auto-resume by re-issuing the bind.
 *
 * @returns {@code true} when a session is bound afterwards; {@code false}
 * when there is no desired session or the (re-)bind failed.
 */
export async function ensureBound(): Promise<boolean> {
  const want = desiredSessionId.value;
  if (!want) return false;
  try {
    await ensureConnected();
  } catch (e) {
    console.warn('[wsStore] ensureBound: connect failed:', e);
    return false;
  }
  if (activeSessionId.value === want && socket.value && !socket.value.closed()) {
    return true;
  }
  try {
    await bindSession(want);
    return activeSessionId.value === want;
  } catch (e) {
    console.warn('[wsStore] ensureBound: rebind failed:', e);
    return false;
  }
}

/**
 * The editor is unmounting / the user is leaving the chat-bearing view.
 * Start the grace timer; if no {@link bindSession} call comes in within
 * {@code SESSION_LEAVE_GRACE_MS}, the session is released
 * server-side via {@code session-unbind}. The WebSocket itself stays
 * open — it belongs to the tab, not the editor.
 */
export function leaveChat(): void {
  if (desiredSessionId.value === null) return;
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
export async function subscribeDocument(path: string): Promise<void> {
  desiredSubscriptions.add(path);
  const sock = await ensureConnected();
  sock.sendOnChannel('documents', 'subscribe', { path } as DocumentSubscribeRequest);
}

/**
 * Drop a documents-channel subscription. Removes from desired-set so it
 * stays gone after Reconnect-Resubscribe.
 */
export async function unsubscribeDocument(path: string): Promise<void> {
  desiredSubscriptions.delete(path);
  documentViewers.delete(path);
  if (socket.value && !socket.value.closed()) {
    socket.value.sendOnChannel('documents', 'unsubscribe', { path } as DocumentSubscribeRequest);
  }
}

/**
 * Subscribe to every {@code documents.changed} frame whose path starts
 * with {@code prefix}. The prefix MUST end with {@code /} so it never
 * matches a path that merely happens to share a name-stem. Adds the
 * prefix to the desired-set so Reconnect-Resubscribe replays it after a
 * socket-swap.
 *
 * <p>Unlike {@link subscribeDocument}, prefix subscriptions don't add
 * the connection to the presence-roster of any document — they're silent
 * watchers used by folder-bound apps (Calendar, Kanban, Slideshow) to
 * react to changes anywhere under the app folder without enumerating
 * individual paths.
 */
export async function subscribeDocumentPrefix(prefix: string): Promise<void> {
  desiredPrefixSubscriptions.add(prefix);
  const sock = await ensureConnected();
  sock.sendOnChannel('documents', 'subscribePrefix',
      { prefix } as DocumentPrefixSubscribeRequest);
}

/**
 * Drop a prefix subscription. Removes from desired-set so it stays gone
 * after Reconnect-Resubscribe.
 */
export async function unsubscribeDocumentPrefix(prefix: string): Promise<void> {
  desiredPrefixSubscriptions.delete(prefix);
  if (socket.value && !socket.value.closed()) {
    socket.value.sendOnChannel('documents', 'unsubscribePrefix',
        { prefix } as DocumentPrefixSubscribeRequest);
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
export function onDocumentChanged(
  path: string,
  handler: DocumentChangedHandler,
): () => void {
  let set = documentChangedListeners.get(path);
  if (!set) {
    set = new Set();
    documentChangedListeners.set(path, set);
  }
  set.add(handler);
  return () => {
    const current = documentChangedListeners.get(path);
    if (!current) return;
    current.delete(handler);
    if (current.size === 0) documentChangedListeners.delete(path);
  };
}

/**
 * Register a callback for every {@code documents.changed} frame whose
 * path starts with {@code prefix}. Returns an unsubscribe function.
 *
 * <p>Folder-bound apps wire this in their {@code setup()} once they know
 * their folder path, paired with the {@link subscribeDocumentPrefix}
 * call. The handler receives the full notification including the
 * changed {@code path}, so the app can re-fetch the specific sub-document
 * (lane, column, slide, …) without enumerating them all upfront.
 */
export function onDocumentChangedPrefix(
  prefix: string,
  handler: DocumentChangedHandler,
): () => void {
  let set = documentChangedPrefixListeners.get(prefix);
  if (!set) {
    set = new Set();
    documentChangedPrefixListeners.set(prefix, set);
  }
  set.add(handler);
  return () => {
    const current = documentChangedPrefixListeners.get(prefix);
    if (!current) return;
    current.delete(handler);
    if (current.size === 0) documentChangedPrefixListeners.delete(prefix);
  };
}

/**
 * Register a callback that fires once per reconnect-recovery cycle for
 * the given prefix. The store emits a synthetic notification with
 * {@code kind: "reconnect"} and {@code path = prefix} after the
 * resubscribe frame went out on the fresh socket. Consumers (typically
 * the {@code useDocumentPrefixReaction} composable) use this to run a
 * full reload — without it, writes that happened while the WS was
 * down stay invisible until the next live change.
 *
 * <p>Returns an unsubscribe.
 */
export function onDocumentPrefixReconnect(
  prefix: string,
  handler: DocumentChangedHandler,
): () => void {
  let set = documentPrefixReconnectListeners.get(prefix);
  if (!set) {
    set = new Set();
    documentPrefixReconnectListeners.set(prefix, set);
  }
  set.add(handler);
  return () => {
    const current = documentPrefixReconnectListeners.get(prefix);
    if (!current) return;
    current.delete(handler);
    if (current.size === 0) documentPrefixReconnectListeners.delete(prefix);
  };
}

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
export function onDocumentNoteChanged(
  path: string,
  handler: DocumentNoteChangedHandler,
): () => void {
  let set = documentNoteChangedListeners.get(path);
  if (!set) {
    set = new Set();
    documentNoteChangedListeners.set(path, set);
  }
  set.add(handler);
  return () => {
    const current = documentNoteChangedListeners.get(path);
    if (!current) return;
    current.delete(handler);
    if (current.size === 0) documentNoteChangedListeners.delete(path);
  };
}

function attachDocumentsListener(sock: BrainWebSocket): void {
  detachDocumentsListener();
  const presenceOff = sock.onChannel<DocumentPresenceNotification>(
    'documents',
    'presence',
    (data) => {
      if (!data || !data.path) return;
      documentViewers.set(data.path, data.viewers ?? []);
    },
  );
  const changedOff = sock.onChannel<DocumentChangedNotification>(
    'documents',
    'changed',
    (data) => {
      if (!data || !data.path) return;
      const exact = documentChangedListeners.get(data.path);
      if (exact && exact.size > 0) {
        for (const handler of Array.from(exact)) {
          try {
            handler(data);
          } catch (e) {
            console.warn(`[wsStore] document-changed handler for '${data.path}' threw:`, e);
          }
        }
      }
      // Prefix listeners — match every registered prefix whose value is
      // a startsWith-prefix of the changed path. Walks the whole map,
      // expected size is small (one prefix per open folder-bound app).
      if (documentChangedPrefixListeners.size > 0) {
        for (const [prefix, handlers] of documentChangedPrefixListeners) {
          if (!data.path.startsWith(prefix)) continue;
          for (const handler of Array.from(handlers)) {
            try {
              handler(data);
            } catch (e) {
              console.warn(
                  `[wsStore] document-changed-prefix handler for '${prefix}' threw:`, e);
            }
          }
        }
      }
    },
  );
  const noteChangedOff = sock.onChannel<DocumentNoteChangedNotification>(
    'documents',
    'note-changed',
    (data) => {
      if (!data || !data.path) return;
      const listeners = documentNoteChangedListeners.get(data.path);
      if (!listeners || listeners.size === 0) return;
      for (const handler of Array.from(listeners)) {
        try {
          handler(data);
        } catch (e) {
          console.warn(`[wsStore] document-note-changed handler for '${data.path}' threw:`, e);
        }
      }
    },
  );
  documentsUnsubscribe = () => {
    try { presenceOff(); } catch { /* ignore */ }
    try { changedOff(); } catch { /* ignore */ }
    try { noteChangedOff(); } catch { /* ignore */ }
  };
}

function detachDocumentsListener(): void {
  if (documentsUnsubscribe) {
    try { documentsUnsubscribe(); } catch { /* ignore */ }
    documentsUnsubscribe = null;
  }
}

function wireDocumentsSocketWatch(): void {
  if (documentsSocketWatchStopped) return;
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
          next.sendOnChannel('documents', 'subscribe', { path } as DocumentSubscribeRequest);
        } catch (e) {
          console.warn(`[wsStore] reconnect-resubscribe failed for path='${path}':`, e);
        }
      }
      for (const prefix of desiredPrefixSubscriptions) {
        try {
          next.sendOnChannel('documents', 'subscribePrefix',
              { prefix } as DocumentPrefixSubscribeRequest);
        } catch (e) {
          console.warn(`[wsStore] reconnect-resubscribe failed for prefix='${prefix}':`, e);
        }
        // Force-reload tick: tell prefix-consumers they may have missed
        // writes while the WS was down so they can refresh. Skipped on
        // the very first socket attach (prev==null) — that's the boot
        // path, no missed writes yet.
        if (prev) {
          const reconnectListeners = documentPrefixReconnectListeners.get(prefix);
          if (reconnectListeners && reconnectListeners.size > 0) {
            const notification: DocumentChangedNotification = {
              path: prefix,
              kind: 'reconnect',
            } as DocumentChangedNotification;
            for (const handler of Array.from(reconnectListeners)) {
              try {
                handler(notification);
              } catch (e) {
                console.warn(
                    `[wsStore] reconnect-tick handler for '${prefix}' threw:`, e);
              }
            }
          }
        }
      }
    }
  }, { immediate: true });
}

// Wire the documents socket-watch lazily on first store use — keeps the
// module-import side-effect-free for tests / SSR.
wireDocumentsSocketWatch();

// ─── pointers-channel ────────────────────────────────────────

/**
 * Subscribe to the live-pointer stream for {@code path}. Enables both
 * sending ({@link sendPointerMove}) and receiving ({@link onPointer} /
 * {@link onPointerLeave}). Adds to the desired-set so
 * Reconnect-Resubscribe replays it. Idempotent on the wire.
 */
export async function subscribePointers(path: string): Promise<void> {
  desiredPointerSubscriptions.add(path);
  const sock = await ensureConnected();
  sock.sendOnChannel('pointers', 'subscribe', { path } as PointerSubscribeRequest);
}

/**
 * Drop a pointers-channel subscription. Removes from desired-set and
 * sends {@code unsubscribe} so the server broadcasts our {@code leave}
 * to the remaining participants.
 */
export async function unsubscribePointers(path: string): Promise<void> {
  desiredPointerSubscriptions.delete(path);
  if (socket.value && !socket.value.closed()) {
    socket.value.sendOnChannel('pointers', 'unsubscribe', { path } as PointerSubscribeRequest);
  }
}

/**
 * Send our current pointer position on {@code path}. Fire-and-forget:
 * dropped silently when the socket is down (the next move replaces it).
 * Callers MUST coalesce high-frequency {@code mousemove} events
 * (requestAnimationFrame / ~30 Hz) before calling this — the server also
 * rate-caps but the client should not flood the socket in the first
 * place. Coordinates are opaque application space.
 */
export function sendPointerMove(
  path: string,
  x: number,
  y: number,
  data?: Record<string, unknown>,
): void {
  const sock = socket.value;
  if (!sock || sock.closed()) return;
  sock.sendOnChannel('pointers', 'pointer-move', { path, x, y, data } as PointerMoveRequest);
}

/** Register a callback for {@code pointer} frames on {@code path}. Returns an unsubscribe. */
export function onPointer(path: string, handler: PointerHandler): () => void {
  let set = pointerListeners.get(path);
  if (!set) {
    set = new Set();
    pointerListeners.set(path, set);
  }
  set.add(handler);
  return () => {
    const current = pointerListeners.get(path);
    if (!current) return;
    current.delete(handler);
    if (current.size === 0) pointerListeners.delete(path);
  };
}

/** Register a callback for {@code pointer-leave} frames on {@code path}. Returns an unsubscribe. */
export function onPointerLeave(path: string, handler: PointerLeaveHandler): () => void {
  let set = pointerLeaveListeners.get(path);
  if (!set) {
    set = new Set();
    pointerLeaveListeners.set(path, set);
  }
  set.add(handler);
  return () => {
    const current = pointerLeaveListeners.get(path);
    if (!current) return;
    current.delete(handler);
    if (current.size === 0) pointerLeaveListeners.delete(path);
  };
}

function attachPointersListener(sock: BrainWebSocket): void {
  detachPointersListener();
  const pointerOff = sock.onChannel<PointerNotification>(
    'pointers',
    'pointer',
    (data) => {
      if (!data || !data.path) return;
      const listeners = pointerListeners.get(data.path);
      if (!listeners || listeners.size === 0) return;
      for (const handler of Array.from(listeners)) {
        try {
          handler(data);
        } catch (e) {
          console.warn(`[wsStore] pointer handler for '${data.path}' threw:`, e);
        }
      }
    },
  );
  const leaveOff = sock.onChannel<PointerLeaveNotification>(
    'pointers',
    'pointer-leave',
    (data) => {
      if (!data || !data.path) return;
      const listeners = pointerLeaveListeners.get(data.path);
      if (!listeners || listeners.size === 0) return;
      for (const handler of Array.from(listeners)) {
        try {
          handler(data);
        } catch (e) {
          console.warn(`[wsStore] pointer-leave handler for '${data.path}' threw:`, e);
        }
      }
    },
  );
  pointersUnsubscribe = () => {
    try { pointerOff(); } catch { /* ignore */ }
    try { leaveOff(); } catch { /* ignore */ }
  };
}

function detachPointersListener(): void {
  if (pointersUnsubscribe) {
    try { pointersUnsubscribe(); } catch { /* ignore */ }
    pointersUnsubscribe = null;
  }
}

function wirePointersSocketWatch(): void {
  if (pointersSocketWatchStopped) return;
  pointersSocketWatchStopped = true;
  watch(socket, (next) => {
    detachPointersListener();
    if (next) {
      attachPointersListener(next);
      // Re-emit desired-set on every fresh socket (idempotent server-side).
      for (const path of desiredPointerSubscriptions) {
        try {
          next.sendOnChannel('pointers', 'subscribe', { path } as PointerSubscribeRequest);
        } catch (e) {
          console.warn(`[wsStore] pointers reconnect-resubscribe failed for path='${path}':`, e);
        }
      }
    }
  }, { immediate: true });
}

wirePointersSocketWatch();

// ─── signals-channel (generic ephemeral per-doc signals) ─────

/**
 * Subscribe to the ephemeral signal stream for {@code path} (compose-run
 * status, …). Adds to the desired-set so Reconnect-Resubscribe replays it.
 * Idempotent on the wire.
 */
export async function subscribeSignals(path: string): Promise<void> {
  desiredSignalSubscriptions.add(path);
  const sock = await ensureConnected();
  sock.sendOnChannel('signals', 'subscribe', { path } as SignalSubscribeRequest);
}

/** Drop a signals-channel subscription. */
export async function unsubscribeSignals(path: string): Promise<void> {
  desiredSignalSubscriptions.delete(path);
  if (socket.value && !socket.value.closed()) {
    socket.value.sendOnChannel('signals', 'unsubscribe', { path } as SignalSubscribeRequest);
  }
}

/** Register a callback for {@code signal} frames on {@code path}. Returns an unsubscribe. */
export function onSignal(path: string, handler: SignalHandler): () => void {
  let set = signalListeners.get(path);
  if (!set) {
    set = new Set();
    signalListeners.set(path, set);
  }
  set.add(handler);
  return () => {
    const current = signalListeners.get(path);
    if (!current) return;
    current.delete(handler);
    if (current.size === 0) signalListeners.delete(path);
  };
}

function attachSignalsListener(sock: BrainWebSocket): void {
  detachSignalsListener();
  const off = sock.onChannel<SignalFrame>('signals', 'signal', (data) => {
    if (!data || !data.path) return;
    const listeners = signalListeners.get(data.path);
    if (!listeners || listeners.size === 0) return;
    for (const handler of Array.from(listeners)) {
      try {
        handler(data);
      } catch (e) {
        console.warn(`[wsStore] signal handler for '${data.path}' threw:`, e);
      }
    }
  });
  signalsUnsubscribe = () => {
    try { off(); } catch { /* ignore */ }
  };
}

function detachSignalsListener(): void {
  if (signalsUnsubscribe) {
    try { signalsUnsubscribe(); } catch { /* ignore */ }
    signalsUnsubscribe = null;
  }
}

function wireSignalsSocketWatch(): void {
  if (signalsSocketWatchStopped) return;
  signalsSocketWatchStopped = true;
  watch(socket, (next) => {
    detachSignalsListener();
    if (next) {
      attachSignalsListener(next);
      for (const path of desiredSignalSubscriptions) {
        try {
          next.sendOnChannel('signals', 'subscribe', { path } as SignalSubscribeRequest);
        } catch (e) {
          console.warn(`[wsStore] signals reconnect-resubscribe failed for path='${path}':`, e);
        }
      }
    }
  }, { immediate: true });
}

wireSignalsSocketWatch();

/**
 * Inform the store that the server-side binding is already in place
 * (e.g. after a {@code session-bootstrap} that creates + binds in one
 * roundtrip). Updates the store state without sending another frame.
 */
export function markBound(sessionId: string): void {
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
export async function unbindNow(): Promise<void> {
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
export function manualReconnect(): void {
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
export function closeConnection(): void {
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
export function useWsConnection(): {
  socket: Ref<BrainWebSocket | null>;
  status: Ref<WsStatus>;
  activeSessionId: Ref<string | null>;
  desiredSessionId: Ref<string | null>;
  reconnectAttempts: Ref<number>;
  maxReconnectAttempts: number;
  lastError: Ref<string | null>;
  isOverlayVisible: ComputedRef<boolean>;
  documentViewers: Map<string, DocumentViewer[]>;
} {
  return {
    socket,
    status,
    activeSessionId,
    desiredSessionId,
    reconnectAttempts,
    maxReconnectAttempts: MAX_RECONNECT_ATTEMPTS,
    lastError,
    isOverlayVisible: computed(
      () => status.value === 'reconnecting' || status.value === 'down',
    ),
    documentViewers,
  };
}
