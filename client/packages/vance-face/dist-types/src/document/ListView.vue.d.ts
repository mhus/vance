import type { ListDocument } from './listItemsCodec';
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
 */
type __VLS_Props = {
    doc: ListDocument;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": (doc: ListDocument) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((doc: ListDocument) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ListView.vue.d.ts.map