/**
 * Active-block highlight — marks the top-level block the selection is in with
 * an `is-active-block` class, so the user always sees which block is "active"
 * (the one the chat treats as "this block"; see planning/app-chat-context.md).
 * Recomputed on every selection change; a single node decoration, cheap.
 */
import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import type { EditorState } from '@tiptap/pm/state';

function activeBlockDecorations(state: EditorState): DecorationSet {
  const sel = state.selection;
  // Top-level block position: the depth-1 ancestor of the caret, or — for a
  // top-level node selection (e.g. a compose block clicked as a whole) — the
  // selected node itself.
  const start = sel.$from.depth >= 1 ? sel.$from.before(1) : sel.from;
  const node = state.doc.nodeAt(start);
  if (!node || node.isText) return DecorationSet.empty;
  return DecorationSet.create(state.doc, [
    Decoration.node(start, start + node.nodeSize, { class: 'is-active-block' }),
  ]);
}

export const ActiveBlock = Extension.create({
  name: 'activeBlock',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('activeBlock'),
        props: {
          decorations(state) {
            return activeBlockDecorations(state);
          },
        },
      }),
    ];
  },
});
