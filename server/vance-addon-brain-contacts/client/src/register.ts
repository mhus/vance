// Side effect: contributes this addon's messages to the host's i18n instance —
// imported here so keys the host resolves (tabLabelKey) exist once registered.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const ContactKind = defineAsyncComponent(() => import('./ContactKind.vue'));

export function register(): void {
  // A `kind: contact` document: its own card, or a reference into the address
  // book. Rendered from the server's answer — the client parses no YAML.
  registerKind({
    id: 'contact',
    matches: (kind) => (kind ?? '').toLowerCase() === 'contact',
    view: ContactKind,
    tabLabelKey: 'contacts.kind.tab',
  });
}
