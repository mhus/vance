type RoleName = 'USER' | 'ASSISTANT' | 'SYSTEM';
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
    /** Max characters for worker truncation (0 = disabled). */
    lineMaxChars?: number;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{}>, {
    worker: boolean;
    lineMaxChars: number;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=MessageBubble.vue.d.ts.map