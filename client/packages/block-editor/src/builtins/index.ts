// Bundled built-in blocks, registered through the SAME block-extension-
// registry that addons use. `vance-callout` is the reference: a real block
// carried end-to-end by the registry (codec via toAttrs/toBody, editor via
// the Tiptap node, read-only surfaces via the Vue view) instead of being
// hard-coded across parser / serializer / proseMirror / editor / BlockView.
//
// Idempotent: registerBuiltInBlocks() runs once per bundle. It is called
// from index.ts on import (covers every consumer) and defensively from
// WorkPageEditor / BlockView setup (covers direct subpath imports).

import { registerBlock } from '../blockRegistry';
import type { BlockExtension } from '../blockRegistry';
import { VanceCallout } from '../extensions';
import CalloutBlockView from './CalloutBlockView.vue';
import { calloutToAttrs, calloutToBody } from './calloutCodec';

const calloutBlock: BlockExtension = {
  fence: 'vance-callout',
  node: VanceCallout,
  view: CalloutBlockView,
  toAttrs: calloutToAttrs,
  toBody: calloutToBody,
  slash: {
    title: 'Callout',
    hint: 'Info / Warn / Note',
    insert: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({
          type: 'vanceCallout',
          attrs: { severity: 'info', title: 'Hinweis', body: '' },
        })
        .run(),
  },
};

let done = false;

/** Register the bundled built-in blocks. Idempotent. */
export function registerBuiltInBlocks(): void {
  if (done) return;
  done = true;
  registerBlock(calloutBlock);
}
