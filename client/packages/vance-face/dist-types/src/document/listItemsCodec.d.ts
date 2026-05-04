/** A single list item. Extra fields are preserved across round-trip
 *  for json/yaml; markdown can only carry `text`. */
export interface ListItem {
    text: string;
    /** Unknown fields the editor doesn't recognise. Re-emitted on save. */
    extra: Record<string, unknown>;
}
export interface ListDocument {
    /** Always `'list'` for list documents — kept generic so future
     *  related kinds can reuse the codec shape. */
    kind: string;
    items: ListItem[];
    /** Unknown top-level fields (json/yaml only). For markdown this is
     *  the residual front-matter map keyed by field name → raw value. */
    extra: Record<string, unknown>;
}
export declare class ListCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseList(body: string, mimeType: string): ListDocument;
export declare function serializeList(doc: ListDocument, mimeType: string): string;
/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the List tab at all. */
export declare function isListMime(mimeType: string | null | undefined): boolean;
//# sourceMappingURL=listItemsCodec.d.ts.map