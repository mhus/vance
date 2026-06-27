import { Node, mergeAttributes } from '@tiptap/core';

/**
 * Catch-all Tiptap node for {@code ```vance-X} fences whose type is not
 * recognised. Stores the raw fence body verbatim so a save round-trips
 * it back to disk — never drop content because the client is older than
 * a writer that emitted a newer fence type.
 */
export const VanceUnknownFence = Node.create({
  name: 'vanceUnknownFence',
  group: 'block',
  atom: true,
  draggable: true,

  addAttributes() {
    return {
      info: { default: '' },
      body: { default: '' },
    };
  },

  parseHTML() {
    return [{ tag: 'pre[data-vance-unknown-fence]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'pre',
      mergeAttributes(HTMLAttributes, {
        'data-vance-unknown-fence': '',
        class: 'vance-unknown-fence',
      }),
      ['div', { class: 'vance-unknown-fence__label' }, `Unknown block: ${HTMLAttributes.info ?? '?'}`],
      ['code', {}, (HTMLAttributes.body as string) ?? ''],
    ];
  },
});
