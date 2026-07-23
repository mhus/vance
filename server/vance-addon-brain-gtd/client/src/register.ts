/**
 * Federation expose `./register` — called by the vance-face host at boot.
 * Contributes the folder-level {@code application:gtd} kind. Resolved via
 * explicit id lookup (resolveKind('application:gtd')); matches() is false.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const GtdAppKind = defineAsyncComponent(() => import('./GtdAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/gtd] register() called');

  registerKind({
    id: 'application:gtd',
    matches: () => false,
    view: GtdAppKind,
  });
}
