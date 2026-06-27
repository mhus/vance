import { Node, mergeAttributes } from '@tiptap/core';

/**
 * Tiptap node for {@code ```vance-toggle} fence blocks. Atomic in v1
 * (collapsible body is a single text field; nested-block rendering is
 * v2). Summary + body live as node attributes.
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
});
