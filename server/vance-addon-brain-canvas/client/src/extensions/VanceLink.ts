import { Node, mergeAttributes } from '@tiptap/core';

/**
 * Tiptap node for {@code ```vance-link} fence blocks. Visual link card
 * (href + title + description). Inline {@code [text](url)} stays the
 * default for prose; this is the rich card variant.
 */
export const VanceLink = Node.create({
  name: 'vanceLink',
  group: 'block',
  atom: true,
  draggable: true,

  addAttributes() {
    return {
      href: { default: '' },
      title: { default: null },
      description: { default: null },
    };
  },

  parseHTML() {
    return [{ tag: 'a[data-vance-link]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'a',
      mergeAttributes(HTMLAttributes, {
        'data-vance-link': '',
        href: HTMLAttributes.href,
        target: '_blank',
        rel: 'noopener noreferrer',
        class: 'vance-link-card',
      }),
      ['div', { class: 'vance-link-card__title' }, (HTMLAttributes.title as string | null) ?? HTMLAttributes.href],
      [
        'div',
        { class: 'vance-link-card__description' },
        (HTMLAttributes.description as string | null) ?? '',
      ],
    ];
  },
});
