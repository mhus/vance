const IMAGE_EXTS = [
    '.png',
    '.jpg',
    '.jpeg',
    '.gif',
    '.webp',
    '.svg',
    '.bmp',
    '.ico',
];
const registry = [
    {
        id: 'image',
        match: (doc) => {
            const m = (doc.mimeType ?? '').toLowerCase();
            if (m.startsWith('image/'))
                return true;
            // Fallback by extension when the server didn't set a mime-type.
            const p = doc.path.toLowerCase();
            return IMAGE_EXTS.some((ext) => p.endsWith(ext));
        },
        mode: 'image',
        editLocation: 'server-side',
    },
    {
        id: 'code',
        // CodeEditor handles markdown, json, yaml, js, ts, py, sh, etc.,
        // and falls back to plain text for unknown mime-types. Catch-all —
        // must stay last.
        match: () => true,
        mode: 'code',
        editLocation: 'client-memory',
    },
];
/**
 * Resolve which binding renders the given document. Returns the first
 * matching entry; falls back to the catch-all code binding (last entry).
 */
export function resolveBinding(doc) {
    for (const entry of registry) {
        if (entry.match(doc))
            return entry;
    }
    return registry[registry.length - 1];
}
//# sourceMappingURL=docTypeRegistry.js.map