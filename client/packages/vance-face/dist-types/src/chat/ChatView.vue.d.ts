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
    /** Active follow-up reply suggestion (reply mode). Rendered as a
     *  ghost bubble below the most-recent assistant message; {@code null}
     *  hides the bubble entirely. Computed by the parent so the
     *  composer (sibling) can use the same value for Space-acceptance. */
    followUpSuggestion?: string | null;
};
/**
 * Pushes a "who is here right now" activity line — called by the
 * parent (ChatApp) after a successful {@code session-who} WS reply.
 * Exposed via {@link defineExpose} below.
 */
declare function pushWhoActivity(names: string[]): void;
declare function appendLocalEcho(message: ChatMessageDto): void;
declare function rollbackLocalEcho(messageId: string): void;
declare const _default: import("vue").DefineComponent<__VLS_Props, {
    appendLocalEcho: typeof appendLocalEcho;
    rollbackLocalEcho: typeof rollbackLocalEcho;
    pushWhoActivity: typeof pushWhoActivity;
}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {
    [x: string]: any;
} & {
    [x: string]: any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    [x: `on${Capitalize<any>}`]: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChatView.vue.d.ts.map