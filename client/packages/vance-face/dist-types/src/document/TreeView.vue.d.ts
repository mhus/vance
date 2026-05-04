import type { TreeDocument, TreeItem } from './treeItemsCodec';
type __VLS_Props = {
    /** Top-level only — the full TreeDocument to edit. */
    doc?: TreeDocument | null;
    /** Recursive only — the children array of the parent item. */
    items?: TreeItem[];
    /** Recursive only — path of the *parent* item in the global tree.
     *  An item at index {@code idx} in {@code items} has the global
     *  path {@code [...pathPrefix, idx]}. Top-level uses {@code []}. */
    pathPrefix?: number[];
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": (doc: TreeDocument) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((doc: TreeDocument) => any) | undefined;
}>, {
    items: TreeItem[];
    pathPrefix: number[];
    doc: TreeDocument | null;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=TreeView.vue.d.ts.map