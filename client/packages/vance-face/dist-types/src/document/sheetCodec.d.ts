export interface SheetCell {
    /** A1-Adresse, kanonisch uppercase (`A1`, `B5`, `AB99`). */
    field: string;
    /** Cell-Inhalt als String. Beginnt mit `=` für Formeln. */
    data: string;
    color?: string;
    background?: string;
    /** Unknown per-cell fields, preserved across round-trip. */
    extra: Record<string, unknown>;
}
export interface SheetDocument {
    kind: string;
    /** Geordnete Liste der angezeigten Spaltenbuchstaben. Optional —
     *  wenn weggelassen, leitet der Editor die Spalten aus den
     *  vorhandenen Cells ab. */
    schema: string[];
    /** Anzahl der angezeigten Zeilen. Optional — wenn `null`, leitet
     *  der Editor sie aus der höchsten referenzierten Zeile ab. */
    rows: number | null;
    cells: SheetCell[];
    /** Unknown top-level fields, preserved across round-trip. */
    extra: Record<string, unknown>;
}
export declare class SheetCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
/** Parse an A1-style address. Returns null on invalid input. */
export declare function parseAddress(addr: string): {
    col: string;
    row: number;
} | null;
/** Convert a 1-based column index (1 = A, 27 = AA) to letters. */
export declare function columnLetterFromIndex(idx: number): string;
/** Inverse: 'A' → 1, 'Z' → 26, 'AA' → 27. Returns 0 on invalid input. */
export declare function columnIndexFromLetter(col: string): number;
export declare function parseSheet(body: string, mimeType: string): SheetDocument;
export declare function serializeSheet(doc: SheetDocument, mimeType: string): string;
export declare function isSheetMime(mimeType: string | null | undefined): boolean;
//# sourceMappingURL=sheetCodec.d.ts.map