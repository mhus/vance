import type { MarvinNodeInsightsDto } from '@vance/generated';
export interface MarvinTreeNode {
    doc: MarvinNodeInsightsDto;
    children: MarvinTreeNode[];
}
type __VLS_Props = {
    node: MarvinTreeNode;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "select-process": any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onSelect-process"?: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=MarvinTreeItem.vue.d.ts.map