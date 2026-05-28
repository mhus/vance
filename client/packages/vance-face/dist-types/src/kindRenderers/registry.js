/**
 * Kind-Renderer-Registry — Compile-Time-Map kind → renderer pair
 * (inline/embedded). Extension: add a View component + an entry
 * here + `wb build face`. No plugin system, no runtime discovery.
 *
 * Spec: specification/inline-and-embedded-content.md §4 / §11.4.
 */
import { defineAsyncComponent } from 'vue';
// Async-loaded views keep the initial bundle small. Each view module
// is fetched on first encounter (chat-stream with a mindmap, an
// embedded PDF, etc.).
const MindmapView = defineAsyncComponent(() => import('@/document/MindmapView.vue'));
const ListView = defineAsyncComponent(() => import('@/document/ListView.vue'));
const TreeView = defineAsyncComponent(() => import('@/document/TreeView.vue'));
const RecordsView = defineAsyncComponent(() => import('@/document/RecordsView.vue'));
const GraphView = defineAsyncComponent(() => import('@/document/GraphView.vue'));
const ChartView = defineAsyncComponent(() => import('@/document/ChartView.vue'));
const SlidesView = defineAsyncComponent(() => import('@/document/SlidesView.vue'));
const ImageView = defineAsyncComponent(() => import('@/document/ImageView.vue'));
const PdfView = defineAsyncComponent(() => import('@/document/PdfView.vue'));
const AudioView = defineAsyncComponent(() => import('@/document/AudioView.vue'));
const VideoView = defineAsyncComponent(() => import('@/document/VideoView.vue'));
const YouTubeView = defineAsyncComponent(() => import('@/document/YouTubeView.vue'));
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
export const kindRegistry = {
    // Vance structured kinds — share their full View across the three
    // modes (editor / inline / embedded) via the `mode` prop.
    mindmap: { inline: MindmapView, embedded: MindmapView, label: 'Mindmap', icon: '🧠' },
    tree: { inline: TreeView, embedded: TreeView, label: 'Tree', icon: '🌳' },
    list: { inline: ListView, embedded: ListView, label: 'List', icon: '•' },
    items: { inline: ListView, embedded: ListView, label: 'Items', icon: '•' },
    records: { inline: RecordsView, embedded: RecordsView, label: 'Records', icon: '▤' },
    graph: { inline: GraphView, embedded: GraphView, label: 'Graph', icon: '🕸' },
    chart: { inline: ChartView, embedded: ChartView, label: 'Chart', icon: '📊' },
    // Slides are embedded-only by design (spec inline-and-embedded-content
    // §8 + doc-kind-slides §1): presentation artefacts belong in the
    // document store, not inline in a chat stream.
    slides: { embedded: SlidesView, label: 'Slides', icon: '📽️' },
    // Binary kinds — embedded only (no inline fence form). Spec §8.
    image: { embedded: ImageView, label: 'Image', icon: '🖼' },
    svg: { embedded: ImageView, label: 'SVG', icon: '🖼' },
    pdf: { embedded: PdfView, label: 'PDF', icon: '📄' },
    audio: { embedded: AudioView, label: 'Audio', icon: '🔊' },
    video: { embedded: VideoView, label: 'Video', icon: '🎬' },
    // External-source embeds — inline-only (no Document load).
    // The fence body carries the URL or video ID.
    youtube: { inline: YouTubeView, label: 'YouTube', icon: '▶' },
    // Sheet remains editor-only for now (cell-formula logic too
    // intertwined with edit handlers to adapt cheaply). The fallback
    // chain in §5 surfaces it as standard Markdown until SheetView
    // grows the `mode` prop.
};
/**
 * Look up a renderer for the given kind + channel combination. Returns
 * `null` when no entry exists or the entry does not provide an adapter
 * for this channel (e.g. `pdf` has no `inline`).
 */
export function resolveRenderer(kind, channel) {
    if (!kind)
        return null;
    const entry = kindRegistry[kind.toLowerCase()];
    if (!entry)
        return null;
    if (channel === 'inline' && !entry.inline)
        return null;
    if (channel === 'embedded' && !entry.embedded)
        return null;
    return entry;
}
/** True when *any* renderer exists for the kind (either channel). */
export function hasRenderer(kind) {
    if (!kind)
        return false;
    return kind.toLowerCase() in kindRegistry;
}
/** Display label for an unknown kind — used by fallback cards. */
export function kindLabel(kind) {
    if (!kind)
        return 'Document';
    const entry = kindRegistry[kind.toLowerCase()];
    return entry?.label ?? kind;
}
/** Display icon for an unknown kind — used by fallback cards. */
export function kindIcon(kind) {
    if (!kind)
        return '📄';
    const entry = kindRegistry[kind.toLowerCase()];
    return entry?.icon ?? '📄';
}
//# sourceMappingURL=registry.js.map