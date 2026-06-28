import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceEmbedNodeView from './VanceEmbedNodeView.vue';

/**
 * Tiptap node for {@code ```vance-embed} fence blocks. Atomic, carries
 * a {@code vance:} URI. The NodeView resolves the URI to a kind-aware
 * preview card (icon, title, path, refresh-on-hover, ⌘+click open) via
 * the host-provided resolver, mirroring the {@code resolveImageSrc}
 * pattern on the Image extension.
 *
 * v1 renders a generic card with the resolved title + kind icon. Full
 * inline-rendering for vance-kinds (mindmap, tree, calendar, …) is v2
 * and will tap into the kind-renderer registry once it's accessible
 * from the editor.
 */
export const VanceEmbed = Node.create({
  name: 'vanceEmbed',
  group: 'block',
  atom: true,
  draggable: true,

  addAttributes() {
    return {
      uri: { default: '' },
    };
  },

  parseHTML() {
    return [{ tag: 'aside[data-vance-embed]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'aside',
      mergeAttributes(HTMLAttributes, {
        'data-vance-embed': '',
        class: 'vance-embed',
      }),
    ];
  },

  addOptions() {
    return {
      /**
       * Host-provided resolver: takes a {@code vance:} URI and
       * returns enough metadata to render the card (id, path, title,
       * kind). Null means "lookup failed" → NodeView shows a
       * not-found placeholder.
       */
      resolveDocumentMeta: null as
        | null
        | ((uri: string) => Promise<EmbedDocMeta | null>),
      /**
       * Lazy accessor for the host-provided kind-aware renderer
       * component. The NodeView calls this on mount; null returns the
       * fallback card. Accessor (not raw component) so the host's
       * reactive injection updates the NodeView when the component
       * becomes available after deferred provide/inject resolves.
       */
      embedComponent: null as null | (() => import('vue').Component | null),
    };
  },

  addNodeView() {
    return VueNodeViewRenderer(VanceEmbedNodeView as never);
  },
});

export interface EmbedDocMeta {
  id: string;
  path: string;
  title: string | null;
  kind: string | null;
  mimeType: string | null;
}
