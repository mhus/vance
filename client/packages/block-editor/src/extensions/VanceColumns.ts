import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceColumnsNodeView from './VanceColumnsNodeView.vue';

/**
 * Multi-column container. Holds one or more {@link VanceColumn} child
 * nodes laid out as CSS-grid columns. Block-level, draggable, content-
 * editable indirectly (the user edits inside each column).
 *
 * Schema constraint: only {@code vanceColumn} children — nested
 * columns-in-columns would compound the layout into unreadable strips
 * and the Markdown round-trip would explode. Adding a column inside a
 * column gets rejected by ProseMirror's schema validation.
 */
export const VanceColumns = Node.create({
  name: 'vanceColumns',
  group: 'block',
  content: 'vanceColumn{2,}',
  // `isolating: true` blocks ProseMirror's standard drag-to-move
  // pipeline (delete source + insert at target) — without it gone the
  // global drag handle copies the container instead of moving it.
  // `defining` was equally over-conservative — the user shouldn't lose
  // the entire columns-block to a Backspace anyway, so the schema-
  // constraint `vanceColumn{2,}` does enough on its own.
  draggable: true,

  parseHTML() {
    return [{ tag: 'div[data-vance-columns]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-vance-columns': '',
        'data-type': 'vanceColumns',
        class: 'vance-columns',
      }),
      0,
    ];
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceColumnsNodeView as never);
  },
});

/**
 * One column slot. Holds normal block content (paragraphs, headings,
 * lists, callouts, code, …) just like the document root, but only
 * mounts inside a {@link VanceColumns} container. {@code width} is a
 * relative fraction (0..1) shared with sibling columns; {@code null}
 * means "equal share".
 */
export const VanceColumn = Node.create({
  name: 'vanceColumn',
  content: 'block+',
  // Same rationale as VanceColumns above — keep the schema constraint
  // (`block+`) as the only thing protecting structure; let standard
  // drag-and-drop semantics flow.

  addAttributes() {
    return {
      width: {
        default: null,
        parseHTML: (el: HTMLElement) => {
          const w = el.getAttribute('data-width');
          if (!w) return null;
          const n = Number(w);
          return Number.isFinite(n) && n > 0 ? n : null;
        },
        renderHTML: (attrs: { width: number | null }) => {
          if (attrs.width == null) return {};
          return { 'data-width': String(attrs.width) };
        },
      },
    };
  },

  parseHTML() {
    return [{ tag: 'div[data-vance-column]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-vance-column': '',
        'data-type': 'vanceColumn',
        class: 'vance-column',
      }),
      0,
    ];
  },
});
