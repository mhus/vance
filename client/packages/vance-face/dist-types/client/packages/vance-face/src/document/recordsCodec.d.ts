/** A single record. `values` holds the schema-field-keyed strings.
 *  `extra` keeps unknown json/yaml keys for round-trip; `overflow`
 *  preserves markdown values that exceeded the schema length. */
export interface RecordsItem {
    values: Record<string, string>;
    extra: Record<string, unknown>;
    overflow: string[];
}
export interface RecordsDocument {
    /** Always `'records'` for records documents — kept generic for
     *  symmetry with the list/tree codecs. */
    kind: string;
    /** Ordered, deduped list of field names. Drives every record's
     *  shape and the editor's column order. Required and non-empty. */
    schema: string[];
    items: RecordsItem[];
    /** Unknown top-level fields (json/yaml) or extra front-matter keys
     *  (markdown). Re-emitted verbatim on save. */
    extra: Record<string, unknown>;
}
export declare class RecordsCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseRecords(body: string, mimeType: string): RecordsDocument;
export declare function serializeRecords(doc: RecordsDocument, mimeType: string): string;
/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the Records tab at all. */
export declare function isRecordsMime(mimeType: string | null | undefined): boolean;
/** Build an empty record matching the document's schema — used by the
 *  editor when the user adds a new row. Every schema field gets the
 *  empty string; `extra` and `overflow` start empty. */
export declare function emptyRecord(schema: string[]): RecordsItem;
//# sourceMappingURL=recordsCodec.d.ts.map