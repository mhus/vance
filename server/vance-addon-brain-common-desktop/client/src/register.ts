/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching `/face/addons`.
 *
 * The common-desktop addon contributes a folder-level application kind:
 * documents with `kind: application` + `app: common-desktop` (i.e.
 * `_app.yaml` manifests) render via `DesktopAppKind`. The host's
 * docTypeRegistry resolves this entry by explicit id lookup, not the
 * generic kind+mime scan, so `matches` returns false on purpose.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const DesktopAppKind = defineAsyncComponent(() => import('./DesktopAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/common-desktop] register() called');
  registerKind({
    id: 'application:common-desktop',
    matches: () => false,
    view: DesktopAppKind,
  });
}
