/**
 * Kind-Renderer-Registry — Compile-Time-Map kind → renderer pair
 * (inline/embedded). Extension: add a View component + an entry
 * here + `wb build face`. No plugin system, no runtime discovery.
 *
 * Spec: specification/inline-and-embedded-content.md §4 / §11.4.
 */
import { type Component } from 'vue';
export type RenderChannel = 'inline' | 'embedded';
export interface KindRenderer {
    /** Render the kind from inline fence-body content. */
    inline?: Component;
    /** Render the kind from a loaded Document (embedded channel). */
    embedded?: Component;
    /** Display label for header / fallback cards. */
    label: string;
    /** Icon (emoji or short text) for the box header. */
    icon: string;
}
/**
 * Registry of Vance-specific rich-content renderers. ONLY Vance kinds
 * land here — code-language fences ({@code ```java}, {@code ```json},
 * {@code ```yaml}, …) intentionally fall back to standard Markdown
 * {@code <pre><code class="language-…">} rendering via marked. A
 * canvas-style code-editor for inline code-snippets is more visual
 * noise than value in chat, and amplifies misbehaving LLM outputs
 * that wrap whole action JSON in {@code ```json} fences.
 *
 * Spec: specification/inline-and-embedded-content.md §4 + §8.
 */
export declare const kindRegistry: Record<string, KindRenderer>;
/**
 * Look up a renderer for the given kind + channel combination. Returns
 * `null` when no entry exists or the entry does not provide an adapter
 * for this channel (e.g. `pdf` has no `inline`).
 */
export declare function resolveRenderer(kind: string | undefined | null, channel: RenderChannel): KindRenderer | null;
/** True when *any* renderer exists for the kind (either channel). */
export declare function hasRenderer(kind: string | undefined | null): boolean;
/** Display label for an unknown kind — used by fallback cards. */
export declare function kindLabel(kind: string | undefined | null): string;
/** Display icon for an unknown kind — used by fallback cards. */
export declare function kindIcon(kind: string | undefined | null): string;
//# sourceMappingURL=registry.d.ts.map