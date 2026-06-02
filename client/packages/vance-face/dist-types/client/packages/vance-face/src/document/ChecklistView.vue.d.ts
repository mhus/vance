import { type ChecklistDocument } from './checklistCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
/**
 * Editor for `kind: checklist` documents — flat list of items with
 * per-item status (open / done / in_progress / review / blocked /
 * needs_info / deferred / delegated / waiting) and optional priority
 * (high / low).
 *
 * Click the status box to cycle open → in_progress → done → open;
 * right-click the box (or use the dropdown) for the full status menu.
 * Click an item text to edit, Enter saves and appends a fresh row,
 * Esc cancels, Backspace on an empty row deletes and focuses the
 * previous item. Priority badge cycles none → high → low → none.
 * Drag the row handle to reorder.
 *
 * Modes:
 *   - `editor`   — full editor surface (used by DocumentApp's
 *                  Checklist tab).
 *   - `inline`   — compact read-only render from a fence body.
 *   - `embedded` — compact read-only render from a loaded Document.
 *
 * Mutations bubble through `update:doc` as a fresh ChecklistDocument;
 * the parent re-serialises into the raw body so the existing Save
 * pipeline writes it back.
 */
type __VLS_Props = {
    mode?: 'editor' | 'inline' | 'embedded';
    doc?: ChecklistDocument;
    content?: string;
    meta?: FenceMeta;
    document?: DocumentDto;
    embedRef?: EmbedRef;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": (doc: ChecklistDocument) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((doc: ChecklistDocument) => any) | undefined;
}>, {
    meta: FenceMeta;
    mode: "editor" | "inline" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChecklistView.vue.d.ts.map