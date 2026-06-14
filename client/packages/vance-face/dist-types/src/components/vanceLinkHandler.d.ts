/**
 * Injection contract for host-level interception of plain clicks on
 * Vance document references (both inline {@code vance:} anchors inside
 * Markdown and the "Open" action on {@link EmbeddedKindBox}).
 *
 * <p>Provide a {@link VanceLinkHandler} under {@link VANCE_LINK_HANDLER_KEY}
 * via Vue's {@code provide()} to take ownership of plain-click navigation
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
 *
 * <p>Lives in its own module (not on {@link MarkdownView}) to keep the
 * symbol identity safe under circular imports — both {@link MarkdownView}
 * and {@link EmbeddedKindBox} read it, and the latter is itself imported
 * by the former.
 */
import type { InjectionKey } from 'vue';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
export interface VanceLinkInterception {
    documentId: string;
    projectId: string;
    embedRef: EmbedRef;
    newTab: boolean;
}
export type VanceLinkHandler = (payload: VanceLinkInterception) => boolean | Promise<boolean>;
export declare const VANCE_LINK_HANDLER_KEY: InjectionKey<VanceLinkHandler>;
//# sourceMappingURL=vanceLinkHandler.d.ts.map