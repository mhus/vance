/**
 * Cross-bundle bridge for in-app navigation.
 *
 * <p>Same shape and the same reason as the WebSocket bridge next door: each
 * copy of {@code @vance/shared} — the host's, plus one per Module-Federation
 * addon — has its own module scope, so the actual implementation is stashed on
 * {@code globalThis} where every copy can read it.
 *
 * <p>What it is for: cortex, chat, inbox and documents are routes inside one
 * shell now, not separate pages. An addon that sends the reader to a document
 * with {@code window.location.href = …} therefore reloads the whole
 * workspace — parses ~400 KB again, rebuilds Vue and Pinia, and drops the
 * WebSocket, which makes this user's presence flicker for everyone else. The
 * addon cannot import the host's router (that is a host-internal path), so the
 * host offers it here.
 *
 * <p><b>Unlike the WS bridge, this one has a default.</b> An unconfigured WS
 * cannot be faked, but an unconfigured navigation can: assigning
 * {@code location.href} is exactly what every one of these call sites did
 * before. So a missing host degrades to the old behaviour rather than
 * throwing — an addon rendered outside the shell (a standalone entry, a test)
 * keeps working, and adopting the bridge is never a risk.
 */

/** Navigate to a same-origin target, in-app when the host can. */
export type VanceNavigate = (url: string) => void;

declare global {
  // eslint-disable-next-line no-var
  var __VANCE_NAVIGATE__: VanceNavigate | null | undefined;
}

const GLOBAL_KEY = '__VANCE_NAVIGATE__' as const;

/**
 * Bind the host's navigation. Called once at boot, before any addon
 * registration — an addon that navigates earlier simply gets the fallback.
 *
 * <p>Idempotent / replaceable: the most recent configuration wins, and
 * callers re-resolve on each use rather than caching.
 */
export function configureVanceNavigate(impl: VanceNavigate): void {
  globalThis[GLOBAL_KEY] = impl;
}

/**
 * Go to {@code url}.
 *
 * <p>Routes inside the shell when the host bound an implementation and the
 * target is one of its routes; falls back to a real page load otherwise —
 * which is also the correct behaviour for an external URL or a standalone
 * entry, so callers do not have to tell the cases apart.
 */
export function vanceNavigate(url: string): void {
  const impl = globalThis[GLOBAL_KEY];
  if (impl) {
    impl(url);
    return;
  }
  window.location.href = url;
}

/** Test-only: forget the binding. Mirrors {@code __resetVanceWs}. */
export function __resetVanceNavigate(): void {
  globalThis[GLOBAL_KEY] = null;
}
