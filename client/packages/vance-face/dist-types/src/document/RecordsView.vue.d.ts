import type { RecordsDocument } from './recordsCodec';
/**
 * Editor for `kind: records` documents — flat table with a fixed
 * schema and one record per row. Click a cell to edit, Enter to
 * commit and jump to the same column of the next row, Tab/Shift+Tab
 * to walk cells left-to-right, Esc to cancel. Bulk-select rows via
 * Cmd/Ctrl-click on the drag-handle column.
 *
 * Mutations bubble up through {@code update:doc} as a fresh
 * {@link RecordsDocument}; the parent re-serialises into the raw
 * body so the existing Save button writes the canonical form.
 *
 * Spec: `specification/doc-kind-records.md`.
 */
type __VLS_Props = {
    doc: RecordsDocument;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": (doc: RecordsDocument) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((doc: RecordsDocument) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=RecordsView.vue.d.ts.map