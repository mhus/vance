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
import { ScriptComposeNodes } from '../extensions/VanceComposeScript';
import ScriptComposeBlockView from './ScriptComposeBlockView.vue';
import { SCRIPT_COMPOSE_KINDS, initialManifest } from './scriptComposeCodec';

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

/**
 * The `vance-compose-<lang>` script blocks: a compose block constrained to one
 * fixed-type task, with a script pane next to the settings YAML. The fence body
 * IS the manifest (stored verbatim in the `yaml` attr), so the codec is the
 * identity map. Run/output wiring is host-injected (`vance:compose-host`).
 */
const scriptComposeBlocks: BlockExtension[] = SCRIPT_COMPOSE_KINDS.map((kind) => ({
  fence: kind.fence,
  node: ScriptComposeNodes[kind.nodeName],
  view: ScriptComposeBlockView,
  toAttrs: (body: string) => ({ yaml: body }),
  toBody: (attrs: Record<string, unknown>) => (attrs.yaml as string | undefined) ?? '',
  slash: {
    title: kind.label,
    hint: kind.hint,
    insert: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({ type: kind.nodeName, attrs: { yaml: initialManifest(kind) } })
        .run(),
  },
}));

let done = false;

/** Register the bundled built-in blocks. Idempotent. */
export function registerBuiltInBlocks(): void {
  if (done) return;
  done = true;
  registerBlock(calloutBlock);
  for (const block of scriptComposeBlocks) registerBlock(block);
}
