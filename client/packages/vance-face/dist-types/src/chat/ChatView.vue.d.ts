import { type BrainWsApi } from '@vance/shared';
/**
 * Mirrors {@code ChatApp.MediationState}. Non-null while the bound
 * session is one Eddie switched us into. Drives the mediation banner
 * and lets {@code send()} intercept the {@code /hub} slash command
 * (spec eddie-engine.md §8.5).
 */
interface MediationState {
    workerProjectName: string;
}
type __VLS_Props = {
    socket: BrainWsApi;
    sessionId: string;
    mediation?: MediationState | null;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    leave: () => any;
    hub: () => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onLeave?: (() => any) | undefined;
    onHub?: (() => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChatView.vue.d.ts.map