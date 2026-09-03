// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const BistromathAppKind = defineAsyncComponent(() => import('./BistromathAppKind.vue'));
const AppViewKind = defineAsyncComponent(() => import('./AppViewKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/bistromath] register() called');

  // Application kind: _app.yaml manifests with app: custom.
  // Resolved by explicit id lookup (resolveKind('application:custom')).
  registerKind({
    id: 'application:custom',
    matches: () => false,
    view: BistromathAppKind,
  });

  // Top-level kind: one view document. Shows a preview against empty state —
  // editing stays in the YAML tab, this answers "did I write it right".
  registerKind({
    id: 'app-view',
    matches: (kind) => (kind ?? '').toLowerCase() === 'app-view',
    view: AppViewKind,
  });
}
