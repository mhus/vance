import type { BrainWsApi } from '@vance/shared';
import type { ChatMessageDto } from '@vance/generated';
/**
 * Mirrors {@code ChatApp.MediationState}. Non-null while the bound
 * session is one Eddie switched us into. Drives the mediation banner
 * and lets the composer (sibling component) intercept the {@code /hub}
 * slash command.
 */
interface MediationState {
    workerProjectName: string;
}
type __VLS_Props = {
    socket: BrainWsApi;
    sessionId: string;
    mediation?: MediationState | null;
    /** Resolved chat-process name — for filtering worker vs main-chat frames. */
    chatProcessName: string | null;
    /** Project that owns this session — used for the header label and
     *  the document-ref store. */
    chatProjectId: string;
};
declare function appendLocalEcho(message: ChatMessageDto): void;
declare function rollbackLocalEcho(messageId: string): void;
declare const _default: import("vue").DefineComponent<__VLS_Props, {
    appendLocalEcho: typeof appendLocalEcho;
    rollbackLocalEcho: typeof rollbackLocalEcho;
}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "project-resolved": (payload: {
        name: string;
        title: string;
    }) => any;
    leave: () => any;
    hub: () => any;
    "speak-message": (content: string) => any;
    "note-activity": () => any;
    "history-loaded": () => any;
    "ask-user-pick": (label: string) => any;
    "wizard-deep-link": (detail: {
        name: string;
        prefill: Record<string, string>;
    }) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onProject-resolved"?: ((payload: {
        name: string;
        title: string;
    }) => any) | undefined;
    onLeave?: (() => any) | undefined;
    onHub?: (() => any) | undefined;
    "onSpeak-message"?: ((content: string) => any) | undefined;
    "onNote-activity"?: (() => any) | undefined;
    "onHistory-loaded"?: (() => any) | undefined;
    "onAsk-user-pick"?: ((label: string) => any) | undefined;
    "onWizard-deep-link"?: ((detail: {
        name: string;
        prefill: Record<string, string>;
    }) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChatView.vue.d.ts.map