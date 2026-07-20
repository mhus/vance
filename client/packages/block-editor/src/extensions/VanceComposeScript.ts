import { Node, mergeAttributes } from '@tiptap/core';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import ScriptComposeNodeView from './ScriptComposeNodeView.vue';
import { SCRIPT_COMPOSE_KINDS, type ScriptComposeKind } from '../builtins/scriptComposeCodec';

/**
 * Tiptap nodes for the `vance-compose-<lang>` script blocks. One node per kind,
 * all sharing {@link ScriptComposeNodeView} (which reads its config by node
 * name). The fence body IS the compose manifest, stored verbatim in the `yaml`
 * attr — same as `vance-compose`. Built against the shared `@tiptap/core` so
 * the host editor accepts them (block-extension-registry contract).
 */
function makeNode(kind: ScriptComposeKind): Node {
  return Node.create({
    name: kind.nodeName,
    group: 'block',
    atom: false,
    content: '',
    draggable: false,
    selectable: false,

    addAttributes() {
      return { yaml: { default: '' } };
    },

    parseHTML() {
      return [{ tag: `aside[data-${kind.fence}]` }];
    },

    renderHTML({ HTMLAttributes }) {
      return [
        'aside',
        mergeAttributes(HTMLAttributes, { [`data-${kind.fence}`]: '', class: 'vance-compose' }),
      ];
    },

    addNodeView() {
      return VueNodeViewRenderer(ScriptComposeNodeView as never);
    },
  });
}

/** One node per script-compose kind, keyed by {@link ScriptComposeKind.nodeName}. */
export const ScriptComposeNodes: Record<string, Node> = Object.fromEntries(
  SCRIPT_COMPOSE_KINDS.map((k) => [k.nodeName, makeNode(k)]),
);
