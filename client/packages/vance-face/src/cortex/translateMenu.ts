/**
 * The Cortex's own contributions to the Extras menu: translate a document,
 * translate the marked passage.
 *
 * <p>These go through {@link registerCortexMenuItem} like an addon's would,
 * rather than into the template beside Save and Share. That is deliberate: an
 * extension point whose only users are external is one nobody has run, and
 * translate exercises every part of it — a visibility rule that depends on the
 * document, an enablement rule that depends on the selection, an async handler
 * that can fail.
 *
 * <p>Registration is idempotent by id, so calling this from a component's
 * `onMounted` is safe across the mount/unmount cycle the workspace router puts
 * the Cortex through.
 */

import { registerCortexMenuItem, type CortexMenuContext } from '@/platform/cortexMenu';
import { isTranslatableDoc } from './translate';

/** What the Cortex hands the entries so they can reach its dialog. */
export interface TranslateMenuHost {
  /** Translation lookup — deferred, so a label follows the active locale. */
  t: (key: string) => string;
  /** Open the translate dialog in one of its two modes. */
  open: (mode: 'document' | 'selection') => void;
}

/** True when the context has a selection belonging to the active document. */
function hasUsableSelection(ctx: CortexMenuContext): boolean {
  const selection = ctx.selection;
  if (!selection || !selection.text.trim()) return false;
  // A stale selection from a tab the reader has since left would translate
  // text that is no longer on screen.
  return selection.docPath === ctx.document?.path;
}

export function registerTranslateMenuItems(host: TranslateMenuHost): void {
  registerCortexMenuItem({
    id: 'cortex:translate-document',
    slot: 'extras',
    sortIndex: 10,
    label: () => host.t('cortex.translate.menuDocument'),
    // Hidden rather than disabled for a spreadsheet or a canvas: those are not
    // documents somebody would expect to translate, so an entry greyed out on
    // every second file is noise.
    visible: (ctx) => ctx.document !== null && isTranslatableDoc(ctx.document),
    run: () => host.open('document'),
  });

  registerCortexMenuItem({
    id: 'cortex:translate-selection',
    slot: 'extras',
    sortIndex: 11,
    label: () => host.t('cortex.translate.menuSelection'),
    // Visible without a selection, only disabled: the entry is how a reader
    // learns that marking a passage first is what it wants.
    visible: (ctx) => ctx.document !== null && isTranslatableDoc(ctx.document),
    enabled: hasUsableSelection,
    run: () => host.open('selection'),
  });
}
