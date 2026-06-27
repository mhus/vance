import { Node, mergeAttributes } from '@tiptap/core';

/**
 * Tiptap node for {@code ```vance-callout} fence blocks. Atomic
 * (non-editable inline) — Severity / title / body live as node
 * attributes. Body is plain text; rich formatting inside callouts is a
 * v2 concern.
 */
export const VanceCallout = Node.create({
  name: 'vanceCallout',
  group: 'block',
  atom: true,
  draggable: true,

  addAttributes() {
    return {
      severity: { default: 'info' },
      title: { default: null },
      body: { default: '' },
    };
  },

  parseHTML() {
    return [{ tag: 'div[data-vance-callout]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-vance-callout': '',
        class: `vance-callout vance-callout--${HTMLAttributes.severity ?? 'info'}`,
      }),
      [
        'div',
        { class: 'vance-callout__title' },
        (HTMLAttributes.title as string | null) ?? '',
      ],
      ['div', { class: 'vance-callout__body' }, (HTMLAttributes.body as string) ?? ''],
    ];
  },
});
