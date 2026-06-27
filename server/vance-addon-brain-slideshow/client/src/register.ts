/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * The slideshow addon contributes a folder-level application kind:
 * documents with {@code kind: application} + {@code app: slideshow}
 * (i.e. {@code _app.yaml} manifests) render via {@link SlideshowAppKind},
 * which adapts the manifest DTO to the existing {@code SlideshowApp}'s
 * (projectId, folder, title) interface. The host's docTypeRegistry
 * resolves this entry by explicit id lookup (resolveKind), not via
 * the generic kind+mime scan, so the {@code matches} predicate
 * returns false on purpose.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const SlideshowAppKind = defineAsyncComponent(() => import('./SlideshowAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/slideshow] register() called');
  registerKind({
    id: 'application:slideshow',
    matches: () => false,
    view: SlideshowAppKind,
  });
}
