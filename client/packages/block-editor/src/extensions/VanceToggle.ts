import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceToggleNodeView from './VanceToggleNodeView.vue';

/**
 * Tiptap node for {@code ```vance-toggle} fence blocks. Atomic —
 * summary + body live as node attributes; the NodeView exposes a
 * chevron-toggle plus summary input and body textarea. Nested-block
 * rendering inside the body remains v2.
 */
export const VanceToggle = Node.create({
  name: 'vanceToggle',
  group: 'block',
  atom: true,
  draggable: true,

  addAttributes() {
    return {
      summary: { default: '' },
      body: { default: '' },
    };
  },

  parseHTML() {
    return [{ tag: 'details[data-vance-toggle]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'details',
      mergeAttributes(HTMLAttributes, { 'data-vance-toggle': '', class: 'vance-toggle' }),
      ['summary', { class: 'vance-toggle__summary' }, (HTMLAttributes.summary as string) ?? ''],
      ['div', { class: 'vance-toggle__body' }, (HTMLAttributes.body as string) ?? ''],
    ];
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceToggleNodeView as never);
  },
});
