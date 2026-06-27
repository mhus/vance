/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * This addon contributes two kinds: the top-level {@code canvas} kind
 * (single block-document, page-level) and the folder-level
 * {@code application:workspace} kind (container that groups canvas
 * pages with an auto-generated index). They live in one addon because
 * Workspace is the natural container for Canvas pages — splitting
 * them was premature.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const CanvasKind = defineAsyncComponent(() => import('./CanvasKind.vue'));
const WorkspaceAppKind = defineAsyncComponent(() => import('./WorkspaceAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/workspace] register() called');

  // Top-level kind: one canvas = one document. Resolved via the
  // matches() predicate (kind === 'canvas').
  registerKind({
    id: 'canvas',
    matches: (kind) => (kind ?? '').toLowerCase() === 'canvas',
    view: CanvasKind,
    editor: CanvasKind,
    tabLabelKey: 'documents.detail.tabCanvas',
  });

  // Application kind: _app.yaml manifests with app: workspace.
  // Resolved via explicit id lookup (resolveKind('application:workspace'))
  // by docTypeRegistry — matches() returns false on purpose.
  registerKind({
    id: 'application:workspace',
    matches: () => false,
    view: WorkspaceAppKind,
  });
}
