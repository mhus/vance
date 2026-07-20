import { Node } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import WorkbookIndexNodeView from './WorkbookIndexNodeView.vue';

/**
 * Tiptap node for the `vance-workbook-index` block — an atom that renders
 * a clickable "jump to the workbook index" card. Contributed by the
 * workbook addon through the block-extension-registry (the first real
 * addon-owned block after the built-in callout reference). No attributes:
 * the on-disk fence is bare (`\`\`\`vance-workbook-index`).
 */
export const WorkbookIndexBlock = Node.create({
  name: 'vanceWorkbookIndex',
  group: 'block',
  atom: true,
  selectable: true,
  draggable: true,

  parseHTML() {
    return [{ tag: 'div[data-vance-workbook-index]' }];
  },

  renderHTML() {
    return ['div', { 'data-vance-workbook-index': '' }];
  },

  addNodeView() {
    return VueNodeViewRenderer(WorkbookIndexNodeView as never);
  },
});
