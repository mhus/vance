import { type TreeDocument, type TreeItem } from './treeItemsCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
type __VLS_Props = {
    /** Top-level only — the full TreeDocument to edit. */
    doc?: TreeDocument | null;
    /** Recursive only — the children array of the parent item. */
    items?: TreeItem[];
    /** Recursive only — path of the *parent* item in the global tree.
     *  An item at index {@code idx} in {@code items} has the global
     *  path {@code [...pathPrefix, idx]}. Top-level uses {@code []}. */
    pathPrefix?: number[];
    /** Three modes (spec §11.2):
     *    - `editor`   — full editor surface (default).
     *    - `inline`   — compact read-only render from `content`.
     *    - `embedded` — compact read-only render from `document`.   */
    mode?: 'editor' | 'inline' | 'embedded';
    /** Inline mode — Markdown bullet hierarchy. */
    content?: string;
    meta?: FenceMeta;
    /** Embedded mode — loaded Document. */
    document?: DocumentDto;
    embedRef?: EmbedRef;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": (doc: TreeDocument) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((doc: TreeDocument) => any) | undefined;
}>, {
    meta: FenceMeta;
    doc: TreeDocument | null;
    mode: "editor" | "inline" | "embedded";
    items: TreeItem[];
    pathPrefix: number[];
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=TreeView.vue.d.ts.map