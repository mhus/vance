import type { CortexDocument } from './types';
/**
 * Binding entry: which DocumentTabShell mode handles this document, and
 * how its edits should be persisted. The first entry whose {@link match}
 * returns true wins.
 *
 * V1 has two modes — {@code 'code'} (CodeEditor) and {@code 'image'}
 * (ImageView read-only). V2 will add {@code 'typed-model'} for the
 * Checklist/List/Tree/Records/Sheet/Chart/Graph views that emit a
 * typed model via {@code @update:doc}; that needs the cortex store to
 * carry a codec-parsed object instead of {@code inlineText}, which is
 * a bigger lift than the V1 wrapper.
 */
export interface DocTypeBinding {
    /** Unique identifier — used for debug logs and future addon dispatch. */
    id: string;
    /** Decides whether this binding handles the given document. */
    match: (doc: CortexDocument) => boolean;
    /**
     * Which built-in shell mode renders the body.
     *  - {@code 'code'}  — CodeEditor with text-selection mirroring
     *  - {@code 'image'} — ImageView, read-only in V1
     */
    mode: 'code' | 'image';
    /**
     * Where edits go.
     *  - {@code 'client-memory'} — DocumentTabShell emits {@code update}
     *    with the new text; cortexStore writes it on save.
     *  - {@code 'server-side'} — read-only in V1; image-modify tools
     *    will land later as server-mediated operations (decision in
     *    planning/cortex.md §6).
     */
    editLocation: 'client-memory' | 'server-side';
}
/**
 * Resolve which binding renders the given document. Returns the first
 * matching entry; falls back to the catch-all code binding (last entry).
 */
export declare function resolveBinding(doc: CortexDocument): DocTypeBinding;
//# sourceMappingURL=docTypeRegistry.d.ts.map