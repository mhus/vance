interface Props {
    /** Kind name (lowercase). Drives icon/label lookup if not given. */
    kind?: string;
    /** Header label. */
    label: string;
    /** Header icon (emoji or short glyph). */
    icon?: string;
    /** Optional secondary line — e.g. document title, file path. */
    title?: string;
}
declare var __VLS_1: {}, __VLS_3: {};
type __VLS_Slots = {} & {
    actions?: (props: typeof __VLS_1) => any;
} & {
    default?: (props: typeof __VLS_3) => any;
};
declare const __VLS_component: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
declare const _default: __VLS_WithSlots<typeof __VLS_component, __VLS_Slots>;
export default _default;
type __VLS_WithSlots<T, S> = T & {
    new (): {
        $slots: S;
    };
};
//# sourceMappingURL=KindBox.vue.d.ts.map