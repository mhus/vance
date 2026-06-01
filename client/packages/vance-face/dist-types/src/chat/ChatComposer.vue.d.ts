import { type BrainWsApi } from '@vance/shared';
import type { ChatMessageDto } from '@vance/generated';
/**
 * Mirrors {@code ChatApp.MediationState}. Non-null while the bound
 * session is one Eddie switched us into. Lets {@link send} intercept
 * the {@code /hub} slash command so it bounces back to Eddie instead
 * of being forwarded to the mediated worker.
 */
interface MediationState {
    workerProjectName: string;
}
type __VLS_Props = {
    socket: BrainWsApi;
    /** Resolved chat-process name — needed to address `process-steer`. */
    chatProcessName: string | null;
    /** Project that owns this chat session — needed for attachment uploads. */
    chatProjectId: string;
    /** Active mediation state, or null when bound to the hub directly. */
    mediation?: MediationState | null;
};
declare function speakMessage(content: string): void;
declare function noteTalkActivity(): void;
/** Open the TTS gate. Called by the parent once the initial REST
 *  history snapshot has loaded — from that point on, every non-USER
 *  {@code chat-message-appended} frame gets spoken when the speaker
 *  is enabled. */
declare function markSpeakerLive(): void;
/** Replace the composer text — used by the wizard prompt-ready
 *  handoff and by the ASK_USER picker click. */
declare function setText(text: string): void;
/** Replace text AND send immediately. Used by the ASK_USER picker
 *  flow: the picker's option label is the canonical reply, no
 *  edit step expected. */
declare function setTextAndSend(text: string): Promise<void>;
declare const _default: import("vue").DefineComponent<__VLS_Props, {
    speakMessage: typeof speakMessage;
    noteTalkActivity: typeof noteTalkActivity;
    markSpeakerLive: typeof markSpeakerLive;
    setText: typeof setText;
    setTextAndSend: typeof setTextAndSend;
}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    hub: () => any;
    "local-echo": (message: ChatMessageDto) => any;
    "rollback-echo": (messageId: string) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onHub?: (() => any) | undefined;
    "onLocal-echo"?: ((message: ChatMessageDto) => any) | undefined;
    "onRollback-echo"?: ((messageId: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChatComposer.vue.d.ts.map