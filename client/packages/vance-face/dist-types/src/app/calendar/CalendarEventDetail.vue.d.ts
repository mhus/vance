import type { CalendarEventUpdateRequest, CalendarEventView, CalendarLaneView } from '@vance/generated';
type __VLS_Props = {
    event: CalendarEventView;
    lanes: CalendarLaneView[];
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    delete: () => any;
    close: () => any;
    update: (patch: CalendarEventUpdateRequest) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onDelete?: (() => any) | undefined;
    onClose?: (() => any) | undefined;
    onUpdate?: ((patch: CalendarEventUpdateRequest) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CalendarEventDetail.vue.d.ts.map