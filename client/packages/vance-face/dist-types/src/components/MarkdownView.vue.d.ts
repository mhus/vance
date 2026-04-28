interface Props {
    /** Raw Markdown source. {@code null}/blank renders empty. */
    source?: string | null;
    /**
     * Compact one-line rendering (no block elements). Useful when
     * the same content appears as a chat-bubble or list-row preview.
     * Default `false` — full block rendering.
     */
    inline?: boolean;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    inline: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=MarkdownView.vue.d.ts.map