/** A tree item. Extra fields are preserved across round-trip for
 *  json/yaml; markdown can only carry `text` + nesting. */
export interface TreeItem {
    text: string;
    children: TreeItem[];
    /** Unknown fields the editor doesn't recognise. Re-emitted on save. */
    extra: Record<string, unknown>;
}
export interface TreeDocument {
    /** Always `'tree'` for tree documents — kept generic for symmetry
     *  with the list codec. */
    kind: string;
    items: TreeItem[];
    /** Unknown top-level fields (json/yaml). For markdown this is the
     *  residual front-matter map keyed by field name → raw value. */
    extra: Record<string, unknown>;
}
export declare class TreeCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseTree(body: string, mimeType: string): TreeDocument;
export declare function serializeTree(doc: TreeDocument, mimeType: string): string;
/** Whether the codec can handle this mime type. */
export declare function isTreeMime(mimeType: string | null | undefined): boolean;
//# sourceMappingURL=treeItemsCodec.d.ts.map