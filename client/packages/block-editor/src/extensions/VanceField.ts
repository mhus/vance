import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceFieldNodeView from './VanceFieldNodeView.vue';

/**
 * Tiptap node for {@code ```vance-field} blocks — one interactive form
 * field whose answer lives inline in the page (`value` block attribute →
 * fence key on save). `fieldType`: choice | multi | dropdown | text |
 * textarea. In work mode the input writes `value` through the normal
 * debounced auto-save; `verdict` / `feedback` are form-resolve action
 * output and are rendered as green/red marking, never written here. A
 * field with a `solution` is checkable server-side; without one it is a
 * plain form element (checklists).
 */
export const VanceField = Node.create({
  name: 'vanceField',
  group: 'block',
  atom: false,
  content: '',
  draggable: false,
  selectable: false,

  addAttributes() {
    return {
      id: { default: '' },                 // stable field id (actions address it)
      fieldType: { default: 'text' },     // choice | multi | dropdown | text | textarea
      question: { default: '' },
      options: { default: [] as string[] },  // choice/multi/dropdown only
      // Dynamic payloads: number (choice/dropdown), number[] (multi),
      // string (text/textarea) — null when unset.
      solution: { default: null },
      value: { default: null },
      verdict: { default: null },          // correct | wrong — action output
      feedback: { default: null },         // action output
    };
  },

  parseHTML() {
    return [{ tag: 'aside[data-vance-field]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, {
        'data-vance-field': '',
        class: 'vance-field',
      }),
    ];
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceFieldNodeView as never);
  },
});
