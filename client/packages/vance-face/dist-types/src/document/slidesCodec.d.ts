/** Deck-level header. All fields optional — Marpit defaults take over
 *  when a field is missing. */
export interface SlidesHeader {
    theme?: string;
    /** `"16:9"` or `"4:3"`. */
    aspect?: string;
    paginate?: boolean;
    /** Applied to every slide that doesn't carry its own `_class`. */
    defaultClass?: string;
    /** Optional Markdown header rendered above every slide. */
    header?: string;
    /** Optional Markdown footer rendered below every slide. */
    footer?: string;
    /** Unknown header fields. Re-emitted on save. */
    extra: Record<string, unknown>;
}
export interface SlidesDocument {
    /** Always `'slides'` for slides documents. */
    kind: string;
    /** Slide Markdown bodies in order. Each entry is one slide. */
    items: string[];
    /** Deck-level metadata (theme, aspect, paginate, …). */
    slides: SlidesHeader;
    /** Unknown top-level fields (json/yaml). For markdown this is the
     *  residual front-matter map (top-level keys other than `kind` and
     *  `slides`). */
    extra: Record<string, unknown>;
}
export declare class SlidesCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseSlides(body: string, mimeType: string): SlidesDocument;
export declare function serializeSlides(doc: SlidesDocument, mimeType: string): string;
/** Whether the codec can handle this mime type — drives the Slides
 *  tab activation in the document editor. */
export declare function isSlidesMime(mimeType: string | null | undefined): boolean;
//# sourceMappingURL=slidesCodec.d.ts.map