/**
 * The Cortex's own contributions to the Extras menu: translate a document,
 * translate the marked passage.
 *
 * <p>The two have deliberately different reach. "Translate…" writes a new
 * file and is therefore offered for prose only; "Translate selection…" writes
 * nothing — the result goes to a dialog and a clipboard — so it is offered for
 * every document with text in it, source code and configs included.
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
import { canTranslateSelection, isTranslatableDocument } from './translate';

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
    // Hidden rather than disabled for a config, a script or a spreadsheet:
    // this entry writes a file, and a translated YAML is a broken YAML. An
    // entry greyed out on every second document would be noise.
    visible: (ctx) => ctx.document !== null && isTranslatableDocument(ctx.document),
    run: () => host.open('document'),
  });

  registerCortexMenuItem({
    id: 'cortex:translate-selection',
    slot: 'extras',
    sortIndex: 11,
    label: () => host.t('cortex.translate.menuSelection'),
    // Wider than the entry above: nothing is written, so a comment in a
    // script or a `description:` in a config is fair game. Visible without a
    // selection and only disabled — the entry is how a reader learns that
    // marking a passage first is what it wants.
    visible: (ctx) => ctx.document !== null && canTranslateSelection(ctx.document),
    enabled: hasUsableSelection,
    run: () => host.open('selection'),
  });
}
