// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const DesignerAppKind = defineAsyncComponent(() => import('./DesignerAppKind.vue'));

/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * The designer addon contributes a folder-level application kind:
 * documents with {@code kind: application} + {@code app: designer}
 * (i.e. {@code _app.yaml} manifests) render via
 * {@link DesignerAppKind}, the design catalogue with sandboxed live
 * previews. The host's docTypeRegistry resolves this entry by explicit
 * id lookup (resolveKind), not via the generic kind+mime scan, so the
 * {@code matches} predicate returns false on purpose.
 */
export function register(): void {

  console.log('[vance-addon/designer] register() called');

  registerKind({
    id: 'application:designer',
    matches: () => false,
    view: DesignerAppKind,
  });
}
