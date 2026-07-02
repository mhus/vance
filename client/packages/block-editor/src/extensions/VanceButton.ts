import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceButtonNodeView from './VanceButtonNodeView.vue';

/**
 * Tiptap node for {@code ```vance-button} blocks — a clickable button that
 * runs a project script. v1 supports {@code type: script} only: clicking
 * runs the {@code script} (a `.js` document) server-side. Config lives in
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
      type: { default: 'script' },   // v1: only "script"
      script: { default: '' },       // vance: URI / path of the .js document
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
      /** Host-provided run: execute the button's script (by ref). */
      runScript: null as null | ((scriptRef: string) => Promise<void>),
    };
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceButtonNodeView as never);
  },
});
