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
    /** Current follow-up suggestion (reply mode). When set AND the
     *  composer is empty, pressing Space accepts the suggestion (writes
     *  it into the input plus a trailing space, shell-autosuggestion
     *  style) instead of inserting the space. */
    followUpSuggestion?: string | null;
    /** Force the narrow-viewport tools layout (burger `⋯` toggle + popup)
     *  regardless of viewport width. Used by hosts that embed the composer
     *  in a fixed-width container (e.g. Cortex's right panel) where the
     *  `max-width: 1024px` media-query gate doesn't fire even though the
     *  composer itself has too little room. */
    compactTools?: boolean;
    /** Host-provided "current file" hint. When set, the attachment
     *  picker becomes a dropdown that offers attaching this existing
     *  document by id (no upload — it's already in the project) in
     *  addition to the regular from-computer file pick. Drives the
     *  Cortex chat panel's "attach active tab" affordance. */
    currentFileSource?: ComposerCurrentFileSource | null;
    /** Lazy-reconnect hook. When set, the composer calls this before
     *  each WS send if {@code socket.closed()} so the parent can swap in
     *  a fresh socket transparently (typical: server-side idle close).
     *  Returns {@code true} when the socket is ready, {@code false} when
     *  the reconnect failed — in which case the parent has already
     *  switched to its failure UI and the send is aborted. */
    ensureConnected?: () => Promise<boolean>;
    /** Stable per-chat identifier used to persist the in-progress
     *  composer draft to {@code sessionStorage}, so the user's typed
     *  text survives a WS reconnect (the parent destroys the composer
     *  via {@code v-if="liveOk"} when the socket drops — e.g. after the
     *  laptop wakes from sleep). When unset, draft persistence is off. */
    draftKey?: string | null;
};
/**
 * Reference to an already-existing document in the chat's project that
 * the host wants to surface as a one-click attachment. {@code label}
 * is whatever the host wants displayed (typically the file path).
 */
export interface ComposerCurrentFileSource {
    documentId: string;
    label: string;
}
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
    "text-changed": (text: string) => any;
    "follow-up-accepted": () => any;
    "focus-changed": (focused: boolean) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onHub?: (() => any) | undefined;
    "onLocal-echo"?: ((message: ChatMessageDto) => any) | undefined;
    "onRollback-echo"?: ((messageId: string) => any) | undefined;
    "onText-changed"?: ((text: string) => any) | undefined;
    "onFollow-up-accepted"?: (() => any) | undefined;
    "onFocus-changed"?: ((focused: boolean) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChatComposer.vue.d.ts.map