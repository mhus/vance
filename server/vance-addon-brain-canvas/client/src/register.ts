/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * The canvas addon contributes a top-level Kind: documents with
 * {@code kind: canvas} render via {@link CanvasKind}. Unlike calendar /
 * kanban / slideshow, this is NOT an {@code application:*} kind — a
 * canvas is a single document, not a folder bundle.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const CanvasKind = defineAsyncComponent(() => import('./CanvasKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/canvas] register() called');
  registerKind({
    id: 'canvas',
    matches: (kind) => (kind ?? '').toLowerCase() === 'canvas',
    view: CanvasKind,
    editor: CanvasKind,
    tabLabelKey: 'documents.detail.tabCanvas',
  });
}
