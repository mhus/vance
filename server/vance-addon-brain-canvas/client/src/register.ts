import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
// Side effect: contributes this addon's messages to the host's i18n instance.
// Imported here and not only from the components, so keys the HOST resolves
// (the `tabLabelKey` below) are available as soon as the addon registers —
// before any of its components mount.
import './i18n';

const CanvasKind = defineAsyncComponent(() => import('./CanvasKind.vue'));
const CanvasbookAppKind = defineAsyncComponent(() => import('./CanvasbookAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/canvas] register() called');

  // Top-level kind: one spatial board = one document. Read-only view;
  // authoring happens inside a canvasbook app.
  registerKind({
    id: 'canvas',
    matches: (kind) => (kind ?? '').toLowerCase() === 'canvas',
    view: CanvasKind,
    tabLabelKey: 'documents.detail.tabCanvas',
  });

  // Application kind: _app.yaml manifests with app: canvasbook.
  // Resolved by explicit id lookup (resolveKind('application:canvasbook')).
  registerKind({
    id: 'application:canvasbook',
    matches: () => false,
    view: CanvasbookAppKind,
  });
}
