import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
// Side effect: contributes this addon's messages to the host's i18n instance.
// Imported here and not only from the components, so keys the HOST resolves
// (the `tabLabelKey` below) are available as soon as the addon registers —
// before any of its components mount.
import './i18n';

const ScribbleKind = defineAsyncComponent(() => import('./ScribbleKind.vue'));
const ScribblebookAppKind = defineAsyncComponent(() => import('./ScribblebookAppKind.vue'));

export function register(): void {

  console.log('[vance-addon/scribble] register() called');

  // Top-level kind: one handwriting sheet = one document. Editable directly
  // (workpage paradigm) — writing is the primary action, there is no
  // read-only mount and no authoring app in between.
  registerKind({
    id: 'scribble',
    matches: (kind) => (kind ?? '').toLowerCase() === 'scribble',
    view: ScribbleKind,
    tabLabelKey: 'documents.detail.tabScribble',
  });

  // Application kind: _app.yaml manifests with app: scribblebook.
  // Resolved by explicit id lookup (resolveKind('application:scribblebook')).
  registerKind({
    id: 'application:scribblebook',
    matches: () => false,
    view: ScribblebookAppKind,
  });
}
