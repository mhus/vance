// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const SearchAppKind = defineAsyncComponent(() => import('./SearchAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/zarniwoop] register() called');

  // Application kind: _app.yaml manifests with app: search.
  // Resolved by explicit id lookup (resolveKind('application:search')).
  registerKind({
    id: 'application:search',
    matches: () => false,
    view: SearchAppKind,
  });
}
