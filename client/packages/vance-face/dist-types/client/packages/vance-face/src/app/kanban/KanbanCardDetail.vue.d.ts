import type { KanbanCardUpdateRequest, KanbanCardView } from '@vance/generated';
type __VLS_Props = {
    card: KanbanCardView;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    delete: () => any;
    close: () => any;
    update: (patch: KanbanCardUpdateRequest) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onDelete?: (() => any) | undefined;
    onClose?: (() => any) | undefined;
    onUpdate?: ((patch: KanbanCardUpdateRequest) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=KanbanCardDetail.vue.d.ts.map