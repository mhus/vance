import { type ListDocument } from './listItemsCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
/**
 * Editor for `kind: list` documents — flat list of items, one row
 * per item. Click an item to edit, press Enter to save and append a
 * fresh row, Esc to cancel, Backspace on an empty row to delete and
 * focus the previous item. The trash icon is the explicit delete
 * affordance for non-empty items. Each row carries a drag handle —
 * grab it to reorder.
 *
 * Mutations bubble up through {@code update:doc} as a fresh
 * {@link ListDocument}. The parent re-serialises into the raw body
 * so the existing Save button writes the canonical form back.
 *
 * Three modes (spec §11.2):
 *   - `editor`  — full editor surface (this file's original use).
 *   - `inline`  — compact read-only render from a fence body in `content`.
 *   - `embedded`— compact read-only render from a loaded Document.
 */
type __VLS_Props = {
    mode?: 'editor' | 'inline' | 'embedded';
    /** Editor mode — full mutable doc. */
    doc?: ListDocument;
    /** Inline mode — Markdown bullet-list (or json/yaml) fence body. */
    content?: string;
    meta?: FenceMeta;
    /** Embedded mode — loaded Document. */
    document?: DocumentDto;
    embedRef?: EmbedRef;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((...args: any) => any) | undefined;
}>, {
    meta: FenceMeta;
    mode: "editor" | "inline" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ListView.vue.d.ts.map