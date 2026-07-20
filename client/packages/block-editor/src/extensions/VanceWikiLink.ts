import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import WikiLinkNodeView from './WikiLinkNodeView.vue';

export interface WikiLinkOptions {
  /**
   * Sync existence check for red-link styling. Host-injected (backed by the
   * wiki's known-slug set). Default `() => true` → neutral link, no red
   * (e.g. a workpage opened outside a wiki, where nothing resolves links).
   */
  resolveWikiLink: (target: string) => boolean;
  /** Navigate to the target, or offer to create it if missing. Host-injected. */
  openWikiLink: (target: string) => void;
}

/**
 * Inline `[[Wikilink]]` node. Atom (single unit, not free-text). The
 * Markdown I/O lives in the inline codec (`markdown/inline.ts`); this is the
 * ProseMirror/editor half. Rendering + click + red-link styling run through
 * the Vue NodeView using the two host-injected callbacks — the editor itself
 * stays wiki-agnostic. See planning/app-wiki.md §3.
 */
export const VanceWikiLink = Node.create<WikiLinkOptions>({
  name: 'wikiLink',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,

  addOptions() {
    return {
      resolveWikiLink: () => true,
      openWikiLink: () => {},
    };
  },

  addAttributes() {
    return {
      target: { default: '' },
      label: { default: '' },
    };
  },

  parseHTML() {
    return [{ tag: 'a[data-wikilink]' }];
  },

  renderHTML({ HTMLAttributes }) {
    const target = (HTMLAttributes.target as string) ?? '';
    const label = (HTMLAttributes.label as string) || target;
    return [
      'a',
      mergeAttributes({ 'data-wikilink': target, class: 'vance-wikilink' }),
      label,
    ];
  },

  addNodeView() {
    return VueNodeViewRenderer(WikiLinkNodeView as never);
  },
});
