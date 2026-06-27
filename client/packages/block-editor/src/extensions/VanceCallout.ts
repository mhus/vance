import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceCalloutNodeView from './VanceCalloutNodeView.vue';

/**
 * Tiptap node for {@code ```vance-callout} fence blocks. Atomic —
 * severity / title / body live as node attributes; the NodeView
 * surfaces them as a dropdown + form inputs for inline Notion-style
 * editing.
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

  addNodeView() {
    // Cast: our Vue component only consumes a subset of NodeViewProps
    // (node, updateAttributes, selected); Tiptap passes the full prop
    // object at runtime regardless of the type-level declaration.
    return VueNodeViewRenderer(VanceCalloutNodeView as never);
  },
});
