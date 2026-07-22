/**
 * Active-block highlight — marks the top-level block the user is working in
 * with an `is-active-block` class, so it's always visible which block is
 * "active" (the one the chat treats as "this block"; planning/app-chat-context.md).
 *
 * Two signals feed it, because custom NodeViews (compose, form, input, …) have
 * their own contenteditable=false widgets whose focus does NOT move the
 * ProseMirror selection:
 *   1. the ProseMirror selection (caret in ordinary text / a node selection);
 *   2. DOM `focusin` inside a block (e.g. clicking a compose block's textarea).
 * The plugin stores the active top-level position and only recomputes from the
 * selection when the selection actually changed — so editing a NodeView (which
 * dispatches attr transactions while the PM caret stays elsewhere) doesn't yank
 * the highlight back.
 */
import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import type { EditorState } from '@tiptap/pm/state';
import type { EditorView } from '@tiptap/pm/view';
import { Decoration, DecorationSet } from '@tiptap/pm/view';

const key = new PluginKey<{ pos: number }>('activeBlock');

/** Start position of the top-level block holding the current selection. */
function selectionBlockStart(state: EditorState): number {
  const $from = state.selection.$from;
  return $from.depth >= 1 ? $from.before(1) : state.selection.from;
}

/** Start position of the top-level block whose DOM contains `target`, or -1. */
function focusedBlockStart(view: EditorView, target: HTMLElement | null): number {
  if (!target) return -1;
  let el: HTMLElement | null = target;
  while (el && el.parentElement && el.parentElement !== view.dom) el = el.parentElement;
  if (!el || el.parentElement !== view.dom) return -1;
  const index = Array.prototype.indexOf.call(view.dom.children, el);
  if (index < 0) return -1;
  let pos = 0;
  for (let i = 0; i < index; i++) pos += view.state.doc.child(i).nodeSize;
  return pos;
}

export const ActiveBlock = Extension.create({
  name: 'activeBlock',
  addProseMirrorPlugins() {
    return [
      new Plugin<{ pos: number }>({
        key,
        state: {
          init: (_cfg, state) => ({ pos: selectionBlockStart(state) }),
          apply: (tr, value, _old, newState) => {
            const meta = tr.getMeta(key) as { pos: number } | undefined;
            if (meta && typeof meta.pos === 'number') return { pos: meta.pos };
            if (tr.selectionSet) return { pos: selectionBlockStart(newState) };
            if (tr.docChanged) return { pos: tr.mapping.map(value.pos) };
            return value;
          },
        },
        props: {
          decorations(state) {
            const st = key.getState(state);
            const pos = st ? st.pos : -1;
            if (pos < 0) return DecorationSet.empty;
            const node = state.doc.nodeAt(pos);
            if (!node || node.isText) return DecorationSet.empty;
            return DecorationSet.create(state.doc, [
              Decoration.node(pos, pos + node.nodeSize, { class: 'is-active-block' }),
            ]);
          },
          handleDOMEvents: {
            focusin(view, event) {
              const pos = focusedBlockStart(view, event.target as HTMLElement | null);
              const cur = key.getState(view.state);
              if (pos >= 0 && (!cur || cur.pos !== pos)) {
                view.dispatch(view.state.tr.setMeta(key, { pos }));
              }
              return false;
            },
          },
        },
      }),
    ];
  },
});
