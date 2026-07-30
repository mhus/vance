/**
 * True when the shell runs inside the Electron desktop wrapper
 * (`@vance/facelift-desktop`), detected by the preload-injected
 * `window.faceliftDesktop` bridge.
 *
 * Desktop is a windowed app on a personal machine, not a pocketable
 * device, so it drops mobile-only affordances — most notably the
 * app-lock / PIN gate.
 */
export function isDesktop(): boolean {
  return typeof window !== 'undefined' && 'faceliftDesktop' in window;
}
