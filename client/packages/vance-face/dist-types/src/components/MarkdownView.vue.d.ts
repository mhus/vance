import { type PropType, type VNode } from 'vue';
import { type EmbedRef } from '@/kindRenderers/parseVanceUri';
/**
 * Optional host-level interceptor for {@code vance:} document links
 * inside rendered Markdown. Provide a function under this key (via
 * Vue's {@code provide()}) to take ownership of plain-click navigation
 * — return {@code true} to suppress the default jump to
 * {@code documents.html}. Returning {@code false} (or not providing a
 * handler) falls back to the default.
 *
 * <p>Cmd/Ctrl/Shift-click is treated as "open in new tab" and bypasses
 * the interceptor by default so the user can always escape into a
 * dedicated browser tab.
 *
 * <p>Used by Cortex to open the file as a tab in its in-place editor
 * instead of navigating away from the page.
 */
export interface VanceLinkInterception {
    documentId: string;
    projectId: string;
    embedRef: EmbedRef;
    newTab: boolean;
}
export type VanceLinkHandler = (payload: VanceLinkInterception) => boolean | Promise<boolean>;
export declare const VANCE_LINK_HANDLER_KEY: import("vue").InjectionKey<VanceLinkHandler>;
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