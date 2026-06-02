export interface QuestionOption {
    label: string;
    description?: string;
}
interface Props {
    /** Full content from the chat message (question + fallback bullets). */
    content: string;
    /** Structured options from {@code meta.askUserOptions}. */
    options: QuestionOption[];
    /** Whether the picker is still actionable (no answering USER msg yet). */
    actionable: boolean;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    pick: (label: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onPick?: ((label: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=QuestionCanvas.vue.d.ts.map