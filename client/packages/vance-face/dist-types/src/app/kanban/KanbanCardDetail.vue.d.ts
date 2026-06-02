import type { KanbanCardUpdateRequest, KanbanCardView } from '@vance/generated';
type __VLS_Props = {
    card: KanbanCardView;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    update: (patch: KanbanCardUpdateRequest) => any;
    delete: () => any;
    close: () => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onUpdate?: ((patch: KanbanCardUpdateRequest) => any) | undefined;
    onDelete?: (() => any) | undefined;
    onClose?: (() => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=KanbanCardDetail.vue.d.ts.map