export type DiagramTheme = 'default' | 'dark' | 'forest' | 'neutral' | 'base';
export type DiagramLook = 'classic' | 'handDrawn';
export declare const DEFAULT_DIALECT = "mermaid";
export interface DiagramHeader {
    /** Mermaid built-in theme name. Default `'default'`. */
    theme: DiagramTheme;
    /** Render look — `'classic'` SVG strokes or `'handDrawn'` (Mermaid 10+). */
    look: DiagramLook;
    /** Optional font-family override; falls back to Mermaid's default. */
    fontFamily?: string;
    /** Unknown header fields, re-emitted on save. */
    extra: Record<string, unknown>;
}
export interface DiagramDocument {
    /** Always `'diagram'`. */
    kind: string;
    /** Source dialect. Default `'mermaid'`; unknown values round-trip
     *  but fall back to the raw editor on the client. */
    dialect: string;
    diagram: DiagramHeader;
    /** Opaque diagram source. May be empty for a freshly created doc. */
    source: string;
    /** Unknown top-level fields plus the reserved markdown-roundtrip
     *  keys (`_preamble`, `_postamble`, `_unparsedBody`). */
    extra: Record<string, unknown>;
}
export declare class DiagramCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
/** Reserved keys in {@link DiagramDocument#extra} carrying the
 *  markdown text fragments around the source fence. */
export declare const EXTRA_PREAMBLE = "_preamble";
export declare const EXTRA_POSTAMBLE = "_postamble";
export declare const EXTRA_UNPARSED_BODY = "_unparsedBody";
export declare function parseDiagram(body: string, mimeType: string): DiagramDocument;
export declare function serializeDiagram(doc: DiagramDocument, mimeType: string): string;
/** Drives the Diagram tab activation in the document editor. */
export declare function isDiagramMime(mimeType: string | null | undefined): boolean;
export declare function emptyDoc(): DiagramDocument;
//# sourceMappingURL=diagramCodec.d.ts.map