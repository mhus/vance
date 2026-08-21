import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const LinksAppKind = defineAsyncComponent(() => import('./LinksAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/links] register() called');

  // Application kind: _app.yaml manifests with app: links.
  // Resolved by explicit id lookup (resolveKind('application:links')).
  registerKind({
    id: 'application:links',
    matches: () => false,
    view: LinksAppKind,
  });
}
