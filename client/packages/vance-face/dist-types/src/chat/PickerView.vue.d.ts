import { type BrainWsApi } from '@vance/shared';
type __VLS_Props = {
    socket: BrainWsApi;
    username: string | null;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "session-picked": (sessionId: string) => any;
    "session-bootstrapped": (sessionId: string) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onSession-picked"?: ((sessionId: string) => any) | undefined;
    "onSession-bootstrapped"?: ((sessionId: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=PickerView.vue.d.ts.map