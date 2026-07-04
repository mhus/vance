import type {
  DocumentChangedNotification,
  PointerLeaveNotification,
  PointerNotification,
} from '@vance/generated';

/**
 * Cross-bundle bridge for WebSocket subscriptions. Lets Module-Federation
 * addons reach into the host's singleton {@code wsConnectionStore} without
 * importing host-internal paths.
 *
 * <p>Same pattern as {@link configurePlatform} for storage / REST: each
 * copy of {@code @vance/shared} (the host's, plus one per addon remote)
 * has its own module-scope state, so we stash the actual implementation
 * on {@code globalThis} where every copy can read it.
 *
 * <p>The host calls {@link configureVanceWs} once at boot with its
 * {@code wsConnectionStore} exports; addons call {@link getVanceWs} (or
 * use the composables in this package that go through it) and get the
 * real singleton — same WebSocket, same subscription list, same
 * reconnect logic.
 */
export interface VanceWsApi {
  /** Subscribe to {@code documents.changed} + {@code documents.presence} for an exact path. */
  subscribeDocument(path: string): Promise<void>;
  unsubscribeDocument(path: string): Promise<void>;
  /** Register a listener for {@code documents.changed} on an exact path. Returns an unsubscribe. */
  onDocumentChanged(
    path: string,
    handler: (notification: DocumentChangedNotification) => void,
  ): () => void;

  /** Subscribe to {@code documents.changed} for every path under {@code prefix} (must end with {@code /}). */
  subscribeDocumentPrefix(prefix: string): Promise<void>;
  unsubscribeDocumentPrefix(prefix: string): Promise<void>;
  /** Register a listener for {@code documents.changed} matched by prefix. Returns an unsubscribe. */
  onDocumentChangedPrefix(
    prefix: string,
    handler: (notification: DocumentChangedNotification) => void,
  ): () => void;

  /**
   * Register a listener fired once per reconnect-resubscribe cycle for
   * the given prefix. The host emits this AFTER the resubscribe frame
   * lands, so addons can run a force-reload to catch up on writes that
   * happened while the WS was down. {@code path} in the notification is
   * the prefix itself; {@code kind} is the literal string
   * {@code "reconnect"}. Returns an unsubscribe.
   */
  onDocumentPrefixReconnect(
    prefix: string,
    handler: (notification: DocumentChangedNotification) => void,
  ): () => void;

  // ── pointers channel (ephemeral live cursors) ──
  /** Subscribe to the live-pointer stream for a path (enables send + receive). */
  subscribePointers(path: string): Promise<void>;
  unsubscribePointers(path: string): Promise<void>;
  /** Send the local pointer position (opaque app-space coords). Fire-and-forget. */
  sendPointerMove(path: string, x: number, y: number, data?: Record<string, unknown>): void;
  /** Register a listener for {@code pointer} frames on a path. Returns an unsubscribe. */
  onPointer(path: string, handler: (notification: PointerNotification) => void): () => void;
  /** Register a listener for {@code pointer-leave} frames on a path. Returns an unsubscribe. */
  onPointerLeave(
    path: string,
    handler: (notification: PointerLeaveNotification) => void,
  ): () => void;
}

declare global {
  // eslint-disable-next-line no-var
  var __VANCE_WS__: VanceWsApi | null | undefined;
}

const GLOBAL_KEY = '__VANCE_WS__' as const;

function read(): VanceWsApi | null {
  return globalThis[GLOBAL_KEY] ?? null;
}

function write(value: VanceWsApi | null): void {
  globalThis[GLOBAL_KEY] = value;
}

/**
 * Bind the host's WebSocket-store implementation. Must be called once at
 * boot, BEFORE any addon-registration that touches subscriptions —
 * otherwise an addon's first {@link getVanceWs} call throws.
 *
 * <p>Idempotent / replaceable — the most recent configuration wins.
 * There is no listener mechanism; callers re-resolve via {@link getVanceWs}
 * on each access rather than caching.
 */
export function configureVanceWs(impl: VanceWsApi): void {
  write(impl);
}

/**
 * Resolve the host-provided WebSocket API. Throws when
 * {@link configureVanceWs} has not been called — there's no default
 * (the WS singleton lives in the host bundle, not here).
 */
export function getVanceWs(): VanceWsApi {
  const api = read();
  if (api === null) {
    throw new Error(
      '@vance/shared: WS API not configured — call configureVanceWs({...}) '
        + 'at app startup before any consumer subscribes.',
    );
  }
  return api;
}

/**
 * Test-only: forget the current bindings. Mirrors {@code __resetPlatform}.
 */
export function __resetVanceWs(): void {
  write(null);
}
