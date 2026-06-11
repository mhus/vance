import type { Component } from 'vue';
import type { CortexDocument } from './types';
/**
 * A renderer entry that knows how to display + edit one class of
 * documents. Phase 1 registers only the code/text renderer; Phase 4
 * adds the image renderer; further plugins (sheet, chart, etc.) plug
 * in here without touching CortexApp.
 *
 * The first entry whose {@link match} returns true wins.
 */
export interface DocTypeRenderer {
    /** Unique identifier — used for debug logs and future addon dispatch. */
    id: string;
    /** Decides whether this renderer handles the given document. */
    match: (doc: CortexDocument) => boolean;
    /** Vue component shown inside the active-tab area. */
    component: Component;
    /**
     * Where edits go.
     *  - {@code 'client-memory'} — Tab-Renderer updates the document
     *    in-memory; cortexStore.saveActive flushes it to the server.
     *  - {@code 'server-side'} — edits are mediated through dedicated
     *    server endpoints (e.g. image transforms); the tab is read-only
     *    from the user's perspective.
     * Phase 1 has only {@code 'client-memory'} renderers.
     */
    editLocation: 'client-memory' | 'server-side';
}
/**
 * Find the renderer that should handle the given document. Returns the
 * first matching entry; falls back to the last registry entry (which
 * v1 guarantees is the catch-all code renderer).
 */
export declare function resolveRenderer(doc: CortexDocument): DocTypeRenderer;
//# sourceMappingURL=docTypeRegistry.d.ts.map