type RoleName = 'USER' | 'ASSISTANT' | 'SYSTEM';
type __VLS_Props = {
    role: RoleName | string;
    content: string;
    /** ISO string or epoch-millis number — both rendered as relative/local. */
    createdAt?: string | number | Date;
    /** True if the bubble is still streaming (no canonical message yet). */
    streaming?: boolean;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=MessageBubble.vue.d.ts.map