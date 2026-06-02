import type { CalendarEventUpdateRequest, CalendarEventView, CalendarLaneView } from '@vance/generated';
type __VLS_Props = {
    event: CalendarEventView;
    lanes: CalendarLaneView[];
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    update: (patch: CalendarEventUpdateRequest) => any;
    delete: () => any;
    close: () => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onUpdate?: ((patch: CalendarEventUpdateRequest) => any) | undefined;
    onDelete?: (() => any) | undefined;
    onClose?: (() => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CalendarEventDetail.vue.d.ts.map