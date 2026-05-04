import type { TreeDocument } from './treeItemsCodec';
/** Document-level mindmap options carried in the `mindmap:` block. */
export interface MindmapOptions {
    theme?: string;
    direction?: string;
    initialExpandLevel?: number;
}
/** Convert a `TreeDocument` (parsed under `kind: mindmap`) into the
 *  markdown form that markmap-lib's Transformer accepts.
 *
 *  Per-item mapping (see spec §5.4):
 *  - Indent: depth × 2 spaces, then `- `.
 *  - Topic: `text`, optionally wrapped in `[…](link)`, optionally
 *    prefixed with `<icon> ` outside the link.
 *  - Multi-line text: continuation lines go after the bullet at
 *    `indent + 2` spaces, like the tree codec writes them. */
export declare function treeToMarkmapMarkdown(doc: TreeDocument): string;
//# sourceMappingURL=mindmapAdapter.d.ts.map