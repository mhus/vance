import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceButtonNodeView from './VanceButtonNodeView.vue';

/**
 * Tiptap node for {@code ```vance-button} blocks — a clickable button that
 * triggers a server-side action. {@code type}: {@code script} (runs the
 * {@code script} `.js` document) or a built-in workbook action such as
 * {@code form-resolve} / {@code form-reset} (no script). Config lives in
 * the fence; running goes through a host callback so the block-editor stays
 * decoupled from REST.
 */
export const VanceButton = Node.create({
  name: 'vanceButton',
  group: 'block',
  atom: false,
  content: '',
  draggable: false,
  selectable: false,

  addAttributes() {
    return {
      type: { default: 'script' },   // script | form-resolve | form-reset | …
      script: { default: '' },       // vance: URI / path of the .js document (type: script)
      title: { default: '' },        // button label
    };
  },

  parseHTML() {
    return [{ tag: 'aside[data-vance-button]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, {
        'data-vance-button': '',
        class: 'vance-button',
      }),
    ];
  },

  addOptions() {
    return {
      /**
       * Host-provided run: execute this button's action server-side and
       * return an optional summary message (score, confirmation) for
       * inline display. The host is responsible for flushing pending
       * editor saves before running (the action reads the page from the
       * DB).
       */
      runButton: null as null
        | ((button: { type: string; script: string; title: string }) => Promise<string | null>),
    };
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceButtonNodeView as never);
  },
});
