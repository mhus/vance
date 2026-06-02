import { type PropType, type VNode } from 'vue';
declare const _default: import("vue").DefineComponent<import("vue").ExtractPropTypes<{
    /** Raw Markdown source. {@code null}/blank renders empty. */
    source: {
        type: PropType<string | null>;
        default: null;
    };
    /**
     * Compact one-line rendering (no block elements). Skips the
     * token walker — chat-bubble / list-row previews shouldn't grow
     * fence canvases.
     */
    inline: {
        type: BooleanConstructor;
        default: boolean;
    };
}>, () => VNode<import("vue").RendererNode, import("vue").RendererElement, {
    [key: string]: any;
}>, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<import("vue").ExtractPropTypes<{
    /** Raw Markdown source. {@code null}/blank renders empty. */
    source: {
        type: PropType<string | null>;
        default: null;
    };
    /**
     * Compact one-line rendering (no block elements). Skips the
     * token walker — chat-bubble / list-row previews shouldn't grow
     * fence canvases.
     */
    inline: {
        type: BooleanConstructor;
        default: boolean;
    };
}>> & Readonly<{}>, {
    source: string | null;
    inline: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, true, {}, any>;
export default _default;
//# sourceMappingURL=MarkdownView.vue.d.ts.map