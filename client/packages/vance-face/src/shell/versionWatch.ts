/**
 * Notice that the deployment moved on under a long-lived session.
 *
 * <p>The workspace is one page now, so a session survives a working day and no
 * navigation picks up a new release on its own. The hard case — a route chunk
 * that no longer exists — is caught in the router's `onError` and reloads.
 * This is the soft case: everything the reader has open still works, the
 * bundle is just older than the server.
 *
 * <p>So it *offers*, and never acts. A forced reload would take a half-written
 * chat turn or an unsaved editor with it, and the reader has no way to know it
 * was coming. The nudge is emitted as a DOM event so the deciding surface —
 * currently nothing, later a topbar hint — can render it without this module
 * knowing about components.
 *
 * <p>Checked on tab focus rather than on a timer: a backgrounded tab that
 * polls is pure cost, and the moment the reader comes back is exactly when a
 * hint is worth showing.
 *
 * <p><b>Rolling deploys make this advisory by nature.</b> `config.json` is
 * written per pod, so while a rollout is in flight the answer alternates
 * between the old and the new build depending on which replica served the
 * request. That is another reason not to force anything on it.
 */

import { loadRuntimeConfig } from '@/platform/runtimeConfig';

/** Fired on `window` when the deployed build differs from the loaded one. */
export const VERSION_DRIFT_EVENT = 'vance:version-drift';

/** Don't re-ask more often than this, however often the tab is focused. */
const MIN_INTERVAL_MS = 5 * 60 * 1000;

let bootBuild: string | null = null;
let lastCheck = 0;
let announced = false;

function buildIdOf(cfg: { buildSha?: string; version?: string } | null): string | null {
  const id = cfg?.buildSha?.trim() || cfg?.version?.trim();
  return id && id !== 'unknown' ? id : null;
}

async function check(): Promise<void> {
  if (announced) return;
  const now = Date.now();
  if (now - lastCheck < MIN_INTERVAL_MS) return;
  lastCheck = now;

  // `loadRuntimeConfig` caches for the page lifetime, which is exactly wrong
  // here — the point is to see a *changed* file. Fetched directly, and a
  // failure is silence: an unreachable config.json says nothing about the
  // deployed version.
  let current: string | null = null;
  try {
    const res = await fetch('./config.json', { cache: 'no-store' });
    if (!res.ok) return;
    current = buildIdOf(await res.json());
  } catch {
    return;
  }
  if (!current || !bootBuild || current === bootBuild) return;

  announced = true;
  window.dispatchEvent(
    new CustomEvent(VERSION_DRIFT_EVENT, { detail: { from: bootBuild, to: current } }),
  );
}

/**
 * Remember the build this page loaded with, then watch for it changing.
 * No-op when the deployment publishes no build id — without one there is
 * nothing to compare and a guess would be worse than silence.
 */
export function startVersionWatch(): void {
  void loadRuntimeConfig().then((cfg) => {
    bootBuild = buildIdOf(cfg);
    if (!bootBuild) return;
    lastCheck = Date.now();
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') void check();
    });
  });
}
