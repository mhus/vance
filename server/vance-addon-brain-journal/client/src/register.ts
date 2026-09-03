/**
 * Federation expose `./register` — called by the vance-face host at boot
 * after fetching {@code /face/addons}.
 *
 * Contributes a single kind: the folder-level {@code application:journal}
 * kind (a diary container of {@code kind: journal-entry} pages, one per
 * day, with calendar navigation). Resolved via explicit id lookup
 * (resolveKind('application:journal')) by the docTypeRegistry —
 * matches() returns false on purpose.
 */
// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const JournalAppKind = defineAsyncComponent(() => import('./JournalAppKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/journal] register() called');

  registerKind({
    id: 'application:journal',
    matches: () => false,
    view: JournalAppKind,
  });
}
