import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';

const BinderAppKind = defineAsyncComponent(() => import('./BinderAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/binder] register() called');

  // Application kind: _app.yaml manifests with app: binder.
  // Resolved by explicit id lookup (resolveKind('application:binder')).
  registerKind({
    id: 'application:binder',
    matches: () => false,
    view: BinderAppKind,
  });
}
