// Side effect: contributes this addon's messages to the host's i18n instance.
import './i18n';
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
import {
  type FinanceTreeDoc,
  isFinanceMime,
  isFinanceParseError,
  parseFinance,
  serializeFinance,
} from './financeCodec';

const FinanceKind = defineAsyncComponent(() => import('./FinanceKind.vue'));

export function register(): void {

  console.log('[vance-addon/finance] register() called');

  // Top-level kind: one finance-tree = one document, edited in Cortex. A codec
  // kind — the shell parses, saves (autosave, dirty state) and 3-way-merges the
  // body; the view only edits the model.
  registerKind<FinanceTreeDoc>({
    id: 'finance-tree',
    matches: (kind, mime) => (kind ?? '').toLowerCase() === 'finance-tree' && isFinanceMime(mime),
    view: FinanceKind,
    parse: (body, mime) => parseFinance(body, mime),
    serialize: (doc, mime) => serializeFinance(doc, mime),
    isParseError: isFinanceParseError,
    tabLabelKey: 'documents.detail.tabFinance',
    parseErrorKey: 'documents.detail.financeParseError',
  });
}
