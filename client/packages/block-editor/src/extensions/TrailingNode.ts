import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';

/**
 * Ensures the document always ends with an empty paragraph so the user
 * can place the cursor after the last block and keep writing.
 *
 * <p>Critical for "hard" trailing blocks — tables, columns, callouts,
 * embeds, images, the `vance-*` atom nodes — which ProseMirror can't put
 * a text cursor after. Without a trailing textblock the doc ends in a
 * leaf and there is nowhere to click to add the next block.
 *
 * <p>Insertion *between* two such blocks is handled by StarterKit's
 * Gapcursor (click into the gap, then type or `/`); this extension covers
 * the end-of-document case, which Gapcursor alone cannot create.
 *
 * <p>Idempotent: once the trailing paragraph exists it is a textblock, so
 * the append short-circuits — no transaction loop, no unbounded growth.
 * The trailing empty paragraph round-trips harmlessly through Markdown.
 */
export const TrailingNode = Extension.create({
  name: 'trailingNode',

  addProseMirrorPlugins() {
    const editor = this.editor;
    return [
      new Plugin({
        key: new PluginKey('trailingNode'),
        appendTransaction: (_transactions, _oldState, state) => {
          // Don't mutate a read-only document model.
          if (!editor.isEditable) return null;
          const { doc, tr, schema } = state;
          const paragraph = schema.nodes.paragraph;
          if (!paragraph) return null;
          const last = doc.lastChild;
          // Already ends in an editable textblock (paragraph/heading/code)?
          // Then there's a cursor home — nothing to do.
          if (last && last.isTextblock) return null;
          return tr.insert(doc.content.size, paragraph.create());
        },
      }),
    ];
  },
});
