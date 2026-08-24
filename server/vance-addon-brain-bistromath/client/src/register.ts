import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const BistromathAppKind = defineAsyncComponent(() => import('./BistromathAppKind.vue'));

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
}
