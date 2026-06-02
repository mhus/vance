import type { ThinkProcessInsightsDto } from '@vance/generated';
type __VLS_Props = {
    /** All processes in the selected session — already loaded by InsightsApp. */
    processes: ThinkProcessInsightsDto[];
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "select-process": (id: string) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onSelect-process"?: ((id: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=SessionTimelineTab.vue.d.ts.map