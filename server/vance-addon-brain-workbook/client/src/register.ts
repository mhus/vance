/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * This addon contributes two kinds: the top-level {@code workpage} kind
 * (single block-document, page-level) and the folder-level
 * {@code application:workbook} kind (container that groups workpage
 * pages with an auto-generated index). They live in one addon because
 * Workbook is the natural container for WorkPages — splitting
 * them was premature.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
import { registerBlock } from '@vance/block-editor/blockRegistry';
import { WorkbookIndexBlock } from './WorkbookIndexBlock';

const WorkPageKind = defineAsyncComponent(() => import('./WorkPageKind.vue'));
const WorkbookAppKind = defineAsyncComponent(() => import('./WorkbookAppKind.vue'));
const WorkbookIndexReadonlyView = defineAsyncComponent(() => import('./WorkbookIndexReadonlyView.vue'));

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/workbook] register() called');

  // Top-level kind: one workpage = one document. Resolved via the
  // matches() predicate (kind === 'workpage').
  registerKind({
    id: 'workpage',
    matches: (kind) => (kind ?? '').toLowerCase() === 'workpage',
    view: WorkPageKind,
    editor: WorkPageKind,
    tabLabelKey: 'documents.detail.tabWorkPage',
  });

  // Application kind: _app.yaml manifests with app: workbook.
  // Resolved via explicit id lookup (resolveKind('application:workbook'))
  // by docTypeRegistry — matches() returns false on purpose.
  registerKind({
    id: 'application:workbook',
    matches: () => false,
    view: WorkbookAppKind,
  });

  // First addon-owned block via the block-extension-registry: a
  // `/workbook-index` slash block that jumps to the workbook index page
  // (WorkbookAppKind catches the `vance:workbook-goto-index` DOM event).
  registerBlock({
    fence: 'vance-workbook-index',
    node: WorkbookIndexBlock,
    view: WorkbookIndexReadonlyView,
    slash: {
      title: 'Workbook Index',
      hint: 'Link block that jumps to the workbook index',
      insert: ({ editor, range }) =>
        editor
          .chain()
          .focus()
          .deleteRange(range)
          .insertContent({ type: 'vanceWorkbookIndex' })
          .run(),
    },
  });
}
