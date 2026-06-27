import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceTocNodeView from './VanceTocNodeView.vue';

/**
 * Tiptap node for {@code ```vance-toc} fence blocks. Atomic, no
 * attributes — the NodeView scans the live editor state for headings
 * and re-renders on transactions, so the ToC always reflects the
 * current page structure without any manual rebuild.
 */
export const VanceToc = Node.create({
  name: 'vanceToc',
  group: 'block',
  atom: true,
  draggable: true,

  parseHTML() {
    return [{ tag: 'aside[data-vance-toc]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, {
        'data-vance-toc': '',
        class: 'vance-toc',
      }),
      ['div', { class: 'vance-toc__label' }, 'Inhaltsverzeichnis'],
    ];
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceTocNodeView as never);
  },
});
