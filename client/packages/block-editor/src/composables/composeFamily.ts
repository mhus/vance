// Shared identity of the compose-family blocks: the full `vance-compose` cell
// plus the `vance-compose-*` script blocks (js/bash/python/agent). Page-level
// batch operations (Run All Until / Clear All Output) act over ALL of them, so
// the node-name set lives here rather than being hard-coded per NodeView.

import jsyaml from 'js-yaml';
import { SCRIPT_COMPOSE_KINDS } from '../builtins/scriptComposeCodec';

/** Every compose-family Tiptap node name. */
export const COMPOSE_NODE_NAMES: ReadonlySet<string> = new Set<string>([
  'vanceCompose',
  ...SCRIPT_COMPOSE_KINDS.map((k) => k.nodeName),
]);

/** UI-only manifest flag `autoRun: false` opts a block out of "Run All Until". */
export function isAutoRunDisabled(src: string): boolean {
  try {
    const p = jsyaml.load(src);
    return !!p && typeof p === 'object' && !Array.isArray(p)
      && (p as Record<string, unknown>).autoRun === false;
  } catch {
    return false;
  }
}
