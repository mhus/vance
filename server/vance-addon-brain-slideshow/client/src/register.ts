/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}. The slideshow addon
 * doesn't contribute a single-doc Kind (its UI is the AppEditor's
 * folder-level Slideshow viewer, not a per-document renderer), so
 * this is currently a no-op announcement. Kept as a symmetry hook
 * for future cross-addon registrations (Hooks, Tools, …) and as the
 * wire-up verification path for the loader.
 */
export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/slideshow] register() called');
}
