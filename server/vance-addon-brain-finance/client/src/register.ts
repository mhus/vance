// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

const FinanceKind = defineAsyncComponent(() => import('./FinanceKind.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/finance] register() called');

  // Top-level kind: one finance-tree = one document, edited in Cortex.
  registerKind({
    id: 'finance-tree',
    matches: (kind) => (kind ?? '').toLowerCase() === 'finance-tree',
    view: FinanceKind,
    tabLabelKey: 'documents.detail.tabFinance',
  });
}
