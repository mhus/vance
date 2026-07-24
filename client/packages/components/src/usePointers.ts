import { onBeforeUnmount, reactive, watch, type Ref } from 'vue';
import type { PointerNotification } from '@vance/generated';
import { getVanceWs } from '@vance/shared';

/**
 * A remote participant's last-known pointer on the watched path. Held in
 * the reactive map keyed by {@link editorId} (one open connection = one
 * pointer; the same user in two tabs shows two pointers). Coordinates are
 * opaque application space — the consumer applies its own transform
 * (zoom / pan) to position the overlay.
 */
export interface RemotePointer {
  editorId: string;
  userId: string;
  displayName: string;
  x: number;
  y: number;
  /** Application-defined extras passed through verbatim (color, …). */
  data?: Record<string, unknown>;
  /** {@code performance.now()} of the last update — drives the TTL fade. */
  lastSeen: number;
}

export interface UsePointersOptions {
  /** Path to participate on. Reactive: switching swaps the subscription. */
  path: Ref<string | null>;
  /**
   * Max outbound moves per second. The framework coalesces
   * {@link UsePointers.report} calls to this rate on
   * {@code requestAnimationFrame}. Default 30 — comfortably under the
   * server's per-connection cap.
   */
  throttleHz?: number;
  /**
   * Drop a remote pointer that hasn't updated within this many ms. The
   * robust primary cleanup — covers lost {@code pointer-leave} frames,
   * cross-pod gaps and crashes. Default 5000.
   */
  ttlMs?: number;
}

export interface UsePointers {
  /**
   * Reactive map of {@code editorId → RemotePointer} for everyone else
   * currently pointing on the path. Iterate its values to render overlays.
   */
  pointers: Map<string, RemotePointer>;
  /**
   * Report the local pointer position (opaque application space).
   * Coalesced to {@link UsePointersOptions.throttleHz}; call it freely
   * from a raw {@code pointermove} handler.
   */
  report: (x: number, y: number, data?: Record<string, unknown>) => void;
}

/**
 * Live-cursor participation for a path on the {@code pointers} Live-WS
 * channel: subscribe on mount, send the local pointer (coalesced), receive
 * and TTL-fade everyone else's. Rendering — and any zoom/pan transform —
 * is the caller's concern; this composable only moves opaque coordinates.
 *
 * <p>Goes through the {@code __VANCE_WS__} bridge so both host editors and
 * Module-Federation addons (Canvas, …) drive the same WebSocket. Renders
 * cleanly (no subs) in test harnesses where the bridge isn't configured.
 *
 * <p>See {@code specification/public/pointers-channel.md} §8.
 */
export function usePointers(options: UsePointersOptions): UsePointers {
  const throttleHz = options.throttleHz ?? 30;
  const minIntervalMs = 1000 / throttleHz;
  const ttlMs = options.ttlMs ?? 5000;

  const pointers = reactive(new Map<string, RemotePointer>());

  let offPointer: (() => void) | null = null;
  let offLeave: (() => void) | null = null;
  let currentPath: string | null = null;

  // ── outbound coalescing ──
  let pending: { x: number; y: number; data?: Record<string, unknown> } | null = null;
  let rafId: number | null = null;
  let lastSentAt = 0;

  function scheduleFlush(): void {
    if (rafId != null) return;
    rafId = requestAnimationFrame(() => {
      rafId = null;
      if (!pending || !currentPath) return;
      const now = performance.now();
      if (now - lastSentAt < minIntervalMs) {
        scheduleFlush();  // too soon — retry next frame, keep pending
        return;
      }
      const ws = tryGetWs();
      if (ws) {
        ws.sendPointerMove(currentPath, pending.x, pending.y, pending.data);
        lastSentAt = now;
      }
      pending = null;
    });
  }

  function report(x: number, y: number, data?: Record<string, unknown>): void {
    if (!currentPath) return;
    pending = { x, y, data };
    scheduleFlush();
  }

  // ── TTL fade of stale remote pointers ──
  const pruneTimer = setInterval(() => {
    const cutoff = performance.now() - ttlMs;
    for (const [editorId, p] of pointers) {
      if (p.lastSeen < cutoff) pointers.delete(editorId);
    }
  }, 1000);

  function ingest(n: PointerNotification): void {
    pointers.set(n.editorId, {
      editorId: n.editorId,
      userId: n.userId,
      displayName: n.displayName,
      x: n.x,
      y: n.y,
      data: n.data,
      lastSeen: performance.now(),
    });
  }

  function teardownPath(): void {
    if (offPointer) { try { offPointer(); } catch { /* ignore */ } offPointer = null; }
    if (offLeave) { try { offLeave(); } catch { /* ignore */ } offLeave = null; }
    if (currentPath) {
      const ws = tryGetWs();
      if (ws) void ws.unsubscribePointers(currentPath).catch(() => { /* socket gone */ });
      currentPath = null;
    }
    pointers.clear();
    pending = null;
  }

  watch(
    options.path,
    (path) => {
      teardownPath();
      if (!path) return;
      const ws = tryGetWs();
      if (!ws) return;
      currentPath = path;
      offPointer = ws.onPointer(path, ingest);
      offLeave = ws.onPointerLeave(path, (n) => { pointers.delete(n.editorId); });
      void ws.subscribePointers(path).catch((e) => {
        console.warn(`[usePointers] subscribe '${path}' failed:`, e);
      });
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    clearInterval(pruneTimer);
    if (rafId != null) { cancelAnimationFrame(rafId); rafId = null; }
    teardownPath();
  });

  return { pointers, report };
}

/** Resolve the WS API without throwing when the bridge isn't configured. */
function tryGetWs(): ReturnType<typeof getVanceWs> | null {
  try {
    return getVanceWs();
  } catch {
    return null;
  }
}
