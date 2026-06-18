import { type QuestionOption } from './QuestionCanvas.vue';
type RoleName = 'USER' | 'ASSISTANT' | 'SYSTEM';
/** ASK_USER picker option (mirrors the {@code label/description} schema
 *  defined in {@code ArthurActionSchema} / {@code EddieActionSchema}).
 *  Kept as a re-export so existing call-sites stay valid; the new
 *  canonical name is {@link QuestionOption} in QuestionCanvas. */
export type AskUserOption = QuestionOption;
type __VLS_Props = {
    role: RoleName | string;
    content: string;
    /** ISO string or epoch-millis number — both rendered as relative/local. */
    createdAt?: string | number | Date;
    /** True if the bubble is still streaming (no canonical message yet). */
    streaming?: boolean;
    /**
     * True for sub-process (worker) chat echoes — recipes spawned by the
     * main chat (e.g. {@code rezept-suche}). Renders compact, green, and
     * truncated to {@link #lineMaxChars} so the worker chatter doesn't
     * compete with the main thread for visual weight. Mirrors the foot
     * client's {@code worker()} channel.
     */
    worker?: boolean;
    /** Optional sub-process name shown as a prefix in worker mode. */
    processName?: string;
    /** Max characters for worker truncation (0 = disabled). Defaults to
     *  the env-configured {@code uiTheme.lineMaxChars}. */
    lineMaxChars?: number;
    /**
     * Optional structured metadata mirroring
     * {@code ChatMessageDto.meta}. Today we only consume
     * {@code askUserOptions}; future keys (typed side-channels) are
     * ignored gracefully.
     */
    meta?: Record<string, unknown>;
    /**
     * When the meta carries picker options, this flag controls whether
     * the buttons are still actionable. Set false once the user has
     * answered (a later USER message landed) so stale buttons grey out
     * instead of double-firing the same answer.
     */
    optionsActionable?: boolean;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    pickOption: any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onPickOption?: ((...args: any) => any) | undefined;
}>, {
    worker: boolean;
    lineMaxChars: number;
    optionsActionable: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=MessageBubble.vue.d.ts.map