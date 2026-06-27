import { Node, mergeAttributes } from '@tiptap/core';

/**
 * Tiptap node for {@code ```vance-dataview} fence blocks. v1 is a
 * placeholder stub — once the {@code kind: dataset} / {@code kind: dataview}
 * tracks land, the renderer will load the referenced source and embed
 * the aggregation result. For now the editor shows the source path.
 */
export const VanceDataview = Node.create({
  name: 'vanceDataview',
  group: 'block',
  atom: true,
  draggable: true,

  addAttributes() {
    return {
      source: { default: '' },
    };
  },

  parseHTML() {
    return [{ tag: 'div[data-vance-dataview]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-vance-dataview': '',
        class: 'vance-dataview-stub',
      }),
      ['div', { class: 'vance-dataview-stub__label' }, 'Dataview embed'],
      ['code', { class: 'vance-dataview-stub__source' }, (HTMLAttributes.source as string) ?? ''],
      [
        'div',
        { class: 'vance-dataview-stub__hint' },
        'Dataview rendering not yet implemented — see planning/kind-canvas.md §7.',
      ],
    ];
  },
});
