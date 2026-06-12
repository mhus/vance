import type { Component } from 'vue';
import { type KindEntry } from '@vance/kind-registry';
import type { CortexDocument } from './types';
/**
 * Symmetric codec interface — type-erased at the registry boundary so
 * the heterogeneous list of bindings can sit in a single array. The
 * concrete view receives the parsed shape it expects; type-checking
 * happens at the per-binding wiring below.
 */
export interface DocCodec {
    parse(body: string, mimeType: string): unknown;
    serialize(doc: unknown, mimeType: string): string;
}
/**
 * Binding entry: which DocumentTabShell mode handles this document, and
 * how its edits should be persisted. The first entry whose {@link match}
 * returns true wins; the catch-all {@code code} entry must stay last.
 *
 * Modes:
 *  - {@code 'code'} — CodeEditor with text-selection mirroring
 *  - {@code 'image'} — ImageView, read-only
 *  - {@code 'typed-model'} — domain view (Checklist/List/Tree/...) that
 *    consumes a codec-parsed model via {@code :doc} and emits
 *    {@code @update:doc}; the shell parses on render and serializes on
 *    update.
 *  - {@code 'kind-registry'} — view + codec resolved from
 *    {@code @vance/kind-registry} (host built-ins + addon contributions
 *    like Calendar). The shell delegates parse/serialize to the
 *    KindEntry; read-only when {@code serialize} is absent.
 */
export type BindingMode = 'code' | 'image' | 'preview' | 'typed-model' | 'kind-registry';
export interface DocTypeBinding {
    /** Unique identifier — used for debug logs and future addon dispatch. */
    id: string;
    /** Body-render strategy. */
    mode: BindingMode;
    /**
     * Where edits go.
     *  - {@code 'client-memory'} — DocumentTabShell emits {@code update}
     *    with the new text; cortexStore writes it on save.
     *  - {@code 'server-side'} — read-only (image, view-only kind entries).
     */
    editLocation: 'client-memory' | 'server-side';
    /** Required for {@code typed-model}: the Vue component to mount. */
    view?: Component;
    /** Required for {@code typed-model}: parse/serialize against inlineText. */
    codec?: DocCodec;
    /** Required for {@code kind-registry}: the resolved entry. */
    kindEntry?: KindEntry;
}
/**
 * Resolve which binding renders the given document.
 *
 * Lookup order:
 *  1. {@code @vance/kind-registry} — addon-contributed Kinds (e.g.
 *     Calendar) and any host built-ins that have migrated to the
 *     registry.
 *  2. Hand-rolled bindings — for the kinds DocumentApp still dispatches
 *     via hard-coded {@code if/else}.
 *  3. Catch-all CodeEditor on the raw inlineText.
 */
export declare function resolveBinding(doc: CortexDocument): DocTypeBinding;
//# sourceMappingURL=docTypeRegistry.d.ts.map