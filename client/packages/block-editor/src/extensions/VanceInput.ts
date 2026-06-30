import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceInputNodeView from './VanceInputNodeView.vue';

/**
 * Tiptap node for {@code ```vance-input} blocks — a single editable text
 * value bound to a plain text document. In work mode the user edits the
 * value and Save writes the whole document; in design mode a toggle picks
 * single-line vs. multi-line (textarea). I/O goes through host callbacks
 * so the block-editor stays decoupled from REST.
 */
export const VanceInput = Node.create({
  name: 'vanceInput',
  group: 'block',
  atom: false,
  content: '',
  draggable: false,
  selectable: false,

  addAttributes() {
    return {
      config: { default: '' },          // vance: URI of the bound text document
      multiline: { default: false },    // single-line input vs. textarea
    };
  },

  parseHTML() {
    return [{ tag: 'aside[data-vance-input]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, {
        'data-vance-input': '',
        class: 'vance-input',
      }),
    ];
  },

  addOptions() {
    return {
      /** Host load: vance: URI → editable body + onSave config (from header). */
      loadInput: null as
        | null
        | ((
            uri: string,
          ) => Promise<{ content: string; runScript: string | null; session: boolean }>),
      /** Host save: write the body back (header preserved, onSave runs). */
      saveInput: null as null | ((uri: string, content: string) => Promise<void>),
      /** Host save: persist the design-mode onSave settings into the header. */
      saveInputSettings: null as
        | null
        | ((uri: string, runScript: string | null, session: boolean) => Promise<void>),
    };
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceInputNodeView as never);
  },
});
