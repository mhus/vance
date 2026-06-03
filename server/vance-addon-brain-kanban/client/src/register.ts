/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}. The kanban addon doesn't
 * contribute a document Kind (its UI is the AppEditor's folder-level
 * KanbanBoard, not a single-doc renderer), so this is currently a
 * no-op announcement. The hook is kept symmetrical with slideshow so
 * the loader can apply the same wire-up to every addon regardless of
 * whether it currently contributes a Kind.
 */
export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/kanban] register() called');
}
