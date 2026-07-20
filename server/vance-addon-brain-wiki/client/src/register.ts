/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * This addon contributes a single kind: the folder-level
 * {@code application:wiki} kind (a container that groups `kind: workpage`
 * pages into a name-addressed link graph with spaces, generated indexes
 * and `[[Wikilink]]` navigation).
 *
 * The top-level {@code workpage} kind is intentionally NOT re-registered
 * here — the workbook addon owns it and the wiki reuses it. If the
 * workbook addon isn't loaded, wiki pages still render through
 * WikiAppKind's own embedded WorkPageEditor, so the wiki remains usable
 * standalone (only the standalone `kind: workpage` cortex tab would be
 * missing). See planning/app-wiki.md §6.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const WikiAppKind = defineAsyncComponent(() => import('./WikiAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/wiki] register() called');

  // Application kind: _app.yaml manifests with app: wiki. Resolved via
  // explicit id lookup (resolveKind('application:wiki')) by the
  // docTypeRegistry — matches() returns false on purpose.
  registerKind({
    id: 'application:wiki',
    matches: () => false,
    view: WikiAppKind,
  });
}
