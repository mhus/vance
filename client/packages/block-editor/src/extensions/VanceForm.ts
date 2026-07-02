import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceFormNodeView from './VanceFormNodeView.vue';

/**
 * Tiptap node for {@code ```vance-form} fence blocks. Carries a
 * {@code config} reference (a {@code vance:} URI) to an <em>edit-config</em>
 * document, which declares a form-field schema + a target data file.
 * The NodeView mounts a host-provided form component (from vance-face)
 * that loads the schema + current values and renders an editable form
 * with explicit Save / Cancel — mirroring the {@code embedComponent}
 * pattern on {@link VanceEmbed} so the block-editor stays decoupled
 * from the form-engine + REST.
 *
 * Reactive-data, Schritt 3 (planning/workspace-reactive-data.md §8).
 */
export const VanceForm = Node.create({
  name: 'vanceForm',
  group: 'block',
  // Same atom/selectable/draggable=false trio as VanceEmbed: keeps the
  // node transparent to ProseMirror mouse handling so the hosted form
  // inputs receive native focus / selection. The inner NodeView wrapper
  // re-asserts contenteditable="true".
  atom: false,
  content: '',
  draggable: false,
  selectable: false,

  addAttributes() {
    return {
      config: { default: '' },
      // Optional recompute script, lives in the fence (not the data file).
      // A vance: URI / path; runs on save.
      saveScript: { default: '' },
      // The form definition (single + typed fields). Block-specific, so it
      // lives in the fence — the data doc keeps only `schema` + `items`.
      // (Static default; updateForm always sets a fresh object, so the
      // shared reference is never mutated in place.)
      form: { default: { single: false, fields: [] } },
    };
  },

  parseHTML() {
    return [{ tag: 'aside[data-vance-form]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, {
        'data-vance-form': '',
        class: 'vance-form',
      }),
    ];
  },

  addOptions() {
    return {
      /**
       * Lazy accessor for the host-provided form component. The
       * NodeView calls this on mount; null returns a fallback notice.
       * Accessor (not raw component) so a deferred provide/inject in the
       * host updates the NodeView once the component is available.
       */
      formComponent: null as null | (() => import('vue').Component | null),
    };
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceFormNodeView as never);
  },
});
