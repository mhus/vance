import type { Component } from 'vue';
import type { CortexDocument } from './types';
import CodeTabRenderer from './renderers/CodeTabRenderer.vue';
import ImageTabRenderer from './renderers/ImageTabRenderer.vue';

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

const registry: DocTypeRenderer[] = [
  {
    id: 'image',
    match: (doc) => {
      const m = (doc.mimeType ?? '').toLowerCase();
      if (m.startsWith('image/')) return true;
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
export function resolveRenderer(doc: CortexDocument): DocTypeRenderer {
  for (const entry of registry) {
    if (entry.match(doc)) return entry;
  }
  return registry[registry.length - 1];
}
