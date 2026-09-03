// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const FeedsAppKind = defineAsyncComponent(() => import('./FeedsAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/centauri] register() called');

  // Application kind: _app.yaml manifests with app: feeds.
  // Resolved by explicit id lookup (resolveKind('application:feeds')).
  registerKind({
    id: 'application:feeds',
    matches: () => false,
    view: FeedsAppKind,
  });
}
