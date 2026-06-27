/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * The workspace addon contributes a folder-level application kind:
 * documents with {@code kind: application} + {@code app: workspace}
 * (i.e. {@code _app.yaml} manifests). The docTypeRegistry resolves
 * this entry by explicit id lookup (resolveKind), not via the generic
 * kind+mime scan, so the {@code matches} predicate returns false on
 * purpose — same convention as kanban / calendar.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const WorkspaceAppKind = defineAsyncComponent(() => import('./WorkspaceAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/workspace] register() called');
  registerKind({
    id: 'application:workspace',
    matches: () => false,
    view: WorkspaceAppKind,
  });
}
