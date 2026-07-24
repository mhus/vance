import { onBeforeUnmount, watch, type Ref } from 'vue';
import type { DocumentChangedNotification } from '@vance/generated';
import { getVanceWs } from '@vance/shared';

/**
 * Live-watch a folder prefix. Sends {@code documents.subscribePrefix}
 * on mount (and on every prefix change), routes each matching
 * {@code documents.changed} frame to the handler, and re-fires once
 * per reconnect so missed-while-offline writes still trigger a refresh.
 *
 * <p>Frame bursts (e.g. {@code app_rebuild} writing
 * {@code _gantt.md} + {@code _conflicts.yaml} as separate writes) are
 * coalesced via a trailing debounce — the handler runs once with the
 * accumulated path list. Default debounce is 120 ms; opt out with
 * {@code debounceMs: 0} (handler fires per-frame).
 *
 * <p>The prefix MUST end with {@code /} — the server rejects anything
 * else. Folder-bound apps (Calendar, Kanban, Slideshow, …) typically
 * pass {@code `${appFolder}/`}.
 *
 * <p>Self-write filtering happens server-side via the writer's
 * {@code editorId}. For tool-style mutations the server writes without
 * an editorId, so every connection (including the originator) gets the
 * event — the originator's UI reload comes through this composable too,
 * which is the intended CQRS-style flow (REST command → server
 * broadcasts → all subscribers refresh identically).
 *
 * <p>Reconnect: when the WS reconnects, the host re-emits each desired
 * prefix-subscription and fires a synthetic notification through this
 * composable with {@code kind: "reconnect"} and {@code path = prefix}.
 * The handler receives a single-element {@code paths} array containing
 * the prefix; treat it as "you may have missed writes — full reload".
 *
 * @param options.prefix Reactive ref of the prefix to watch (must end
 *   with {@code /}). {@code null} suspends the subscription.
 * @param options.onRemoteChange Called with the accumulated path list
 *   of one debounced batch, plus the matching notification objects in
 *   delivery order. Apps that don't care about path-level granularity
 *   can ignore the arguments and just trigger a full reload.
 * @param options.debounceMs Trailing-debounce window in milliseconds
 *   (default 120). Set to 0 to invoke the handler per-frame.
 */
export function useDocumentPrefixReaction(options: {
  prefix: Ref<string | null>;
  onRemoteChange: (
    paths: string[],
    notifications: DocumentChangedNotification[],
  ) => void | Promise<void>;
  debounceMs?: number;
}): void {
  const debounceMs = options.debounceMs ?? 120;
  let activePrefix: string | null = null;
  let unsubscribeChange: (() => void) | null = null;
  let unsubscribeReconnect: (() => void) | null = null;
  let pendingPaths: string[] = [];
  let pendingNotifications: DocumentChangedNotification[] = [];
  let debounceTimer: ReturnType<typeof setTimeout> | null = null;

  function flush(): void {
    debounceTimer = null;
    if (pendingPaths.length === 0) return;
    const paths = pendingPaths;
    const notifications = pendingNotifications;
    pendingPaths = [];
    pendingNotifications = [];
    try {
      const result = options.onRemoteChange(paths, notifications);
      if (result && typeof (result as Promise<void>).then === 'function') {
        (result as Promise<void>).catch((e) => {
          console.warn('[useDocumentPrefixReaction] onRemoteChange rejected:', e);
        });
      }
    } catch (e) {
      console.warn('[useDocumentPrefixReaction] onRemoteChange threw:', e);
    }
  }

  function accept(notification: DocumentChangedNotification): void {
    if (!notification || !notification.path) return;
    pendingPaths.push(notification.path);
    pendingNotifications.push(notification);
    if (debounceMs <= 0) {
      flush();
      return;
    }
    if (debounceTimer != null) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(flush, debounceMs);
  }

  function teardown(): void {
    if (debounceTimer != null) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    pendingPaths = [];
    pendingNotifications = [];
    if (unsubscribeChange) {
      try { unsubscribeChange(); } catch { /* noop */ }
      unsubscribeChange = null;
    }
    if (unsubscribeReconnect) {
      try { unsubscribeReconnect(); } catch { /* noop */ }
      unsubscribeReconnect = null;
    }
    if (activePrefix) {
      const ws = tryGetWs();
      if (ws) {
        void ws.unsubscribeDocumentPrefix(activePrefix).catch(() => {
          /* socket already gone — server cleaned on close */
        });
      }
      activePrefix = null;
    }
  }

  watch(
    options.prefix,
    (next) => {
      teardown();
      if (!next) return;
      const ws = tryGetWs();
      if (!ws) return;
      activePrefix = next;
      unsubscribeChange = ws.onDocumentChangedPrefix(next, accept);
      unsubscribeReconnect = ws.onDocumentPrefixReconnect(next, accept);
      void ws.subscribeDocumentPrefix(next).catch((e) => {
        // Connection not ready or rejected — log and let
        // reconnect-resubscribe pick it up on the next socket.
        console.warn(
          `[useDocumentPrefixReaction] subscribePrefix '${next}' failed:`, e);
      });
    },
    { immediate: true },
  );

  onBeforeUnmount(teardown);
}

/**
 * Resolve the WS API without throwing if the bridge isn't configured.
 * Used by the composable's setup path so component test harnesses that
 * never call {@code configureVanceWs} render cleanly (no subs, no
 * handlers) instead of exploding at mount.
 */
function tryGetWs(): ReturnType<typeof getVanceWs> | null {
  try {
    return getVanceWs();
  } catch {
    return null;
  }
}
