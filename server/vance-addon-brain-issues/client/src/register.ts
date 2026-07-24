/**
 * Federation expose `./register` — registers the folder-level
 * {@code application:issues} kind. matches() is false (explicit id lookup).
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const IssuesAppKind = defineAsyncComponent(() => import('./IssuesAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/issues] register() called');
  registerKind({ id: 'application:issues', matches: () => false, view: IssuesAppKind });
}
