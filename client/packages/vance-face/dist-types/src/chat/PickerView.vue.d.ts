import { type BrainWsApi } from '@vance/shared';
type __VLS_Props = {
    socket: BrainWsApi;
    username: string | null;
};
type __VLS_PublicProps = __VLS_Props & {
    /**
     * Two-way bound with ChatApp's {@code pickerProjectName} — keeps the
     * picker's selection in sync with the URL state. Writes from inside
     * (user clicks) propagate up; writes from outside (popstate) propagate
     * back down.
     */
    'selectedProject'?: string | null;
};
declare const _default: import("vue").DefineComponent<__VLS_PublicProps, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {
    "update:selectedProject": (value: string | null) => any;
} & {
    "project-resolved": any;
    "session-picked": any;
    "session-bootstrapped": any;
    "focus-main": any;
    "project-pick": any;
}, string, import("vue").PublicProps, Readonly<__VLS_PublicProps> & Readonly<{
    "onProject-resolved"?: ((...args: any) => any) | undefined;
    "onUpdate:selectedProject"?: ((value: string | null) => any) | undefined;
    "onSession-picked"?: ((...args: any) => any) | undefined;
    "onSession-bootstrapped"?: ((...args: any) => any) | undefined;
    "onFocus-main"?: ((...args: any) => any) | undefined;
    "onProject-pick"?: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=PickerView.vue.d.ts.map