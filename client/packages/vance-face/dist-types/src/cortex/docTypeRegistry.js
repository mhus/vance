import CodeTabRenderer from './renderers/CodeTabRenderer.vue';
import ImageTabRenderer from './renderers/ImageTabRenderer.vue';
const registry = [
    {
        id: 'image',
        match: (doc) => {
            const m = (doc.mimeType ?? '').toLowerCase();
            if (m.startsWith('image/'))
                return true;
            // Fallback by extension when the server didn't set a mime-type.
            const p = doc.path.toLowerCase();
            return p.endsWith('.png') || p.endsWith('.jpg') || p.endsWith('.jpeg')
                || p.endsWith('.gif') || p.endsWith('.webp') || p.endsWith('.svg')
                || p.endsWith('.bmp') || p.endsWith('.ico');
        },
        component: ImageTabRenderer,
        // Read-only in v1 — image-modify tools land in a later iteration
        // (decision in planning/cortex.md §6 between WS tool roundtrip
        // and dedicated server endpoints is still open).
        editLocation: 'server-side',
    },
    {
        id: 'code',
        // CodeEditor handles markdown, json, yaml, js, ts, py, sh, etc.,
        // and falls back to plain text for unknown mime-types. Catch-all
        // — must stay last.
        match: () => true,
        component: CodeTabRenderer,
        editLocation: 'client-memory',
    },
];
/**
 * Find the renderer that should handle the given document. Returns the
 * first matching entry; falls back to the last registry entry (which
 * v1 guarantees is the catch-all code renderer).
 */
export function resolveRenderer(doc) {
    for (const entry of registry) {
        if (entry.match(doc))
            return entry;
    }
    return registry[registry.length - 1];
}
//# sourceMappingURL=docTypeRegistry.js.map