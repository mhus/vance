interface Props {
    /** Page title shown in the topbar. */
    title: string;
    /** Breadcrumb segments left-to-right (e.g. `['Project foo', 'Session bar']`). */
    breadcrumbs?: string[];
    /**
     * Connection-state dot in the topbar. Only chat-editor sets this; REST-only
     * editors omit the prop and the dot is hidden.
     */
    connectionState?: 'connected' | 'connecting' | 'disconnected';
}
declare var __VLS_1: {}, __VLS_3: {}, __VLS_5: {}, __VLS_7: {};
type __VLS_Slots = {} & {
    'topbar-extra'?: (props: typeof __VLS_1) => any;
} & {
    sidebar?: (props: typeof __VLS_3) => any;
} & {
    default?: (props: typeof __VLS_5) => any;
} & {
    'right-panel'?: (props: typeof __VLS_7) => any;
};
declare const __VLS_component: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    breadcrumbs: string[];
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
declare const _default: __VLS_WithSlots<typeof __VLS_component, __VLS_Slots>;
export default _default;
type __VLS_WithSlots<T, S> = T & {
    new (): {
        $slots: S;
    };
};
//# sourceMappingURL=EditorShell.vue.d.ts.map