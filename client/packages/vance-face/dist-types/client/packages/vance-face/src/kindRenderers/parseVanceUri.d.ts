/**
 * `vance:` URI parser — converts a Markdown link/image href into an
 * `EmbedRef` suitable for the embedded-channel renderer.
 *
 * Spec: specification/inline-and-embedded-content.md §3.1 / §3.2 /
 * §11.7.
 *
 * URI forms:
 *   vance:/<path>?kind=<kind>                  current project
 *   vance://<projectId>/<path>?kind=<kind>     cross-project
 *
 * Cross-tenant is intentionally not part of the schema — any URI
 * with a path that contains an explicit tenant segment is parsed
 * the same way as a cross-project URI; tenant boundary enforcement
 * lives at the resolver layer.
 */
export interface EmbedRef {
    /** Document path (URI-decoded), 1:1 to `DocumentDocument.path`. */
    path: string;
    /** Project name when explicit (`vance://<project>/...`); else undefined = current project. */
    project?: string;
    /** `?kind=` query param when present — render-mode hint, optional. */
    kindHint?: string;
    /** Effective render mode after applying defaults + overrides. */
    mode: 'preview' | 'reference';
    /** `?caption=` query param when present. */
    caption?: string;
    /** Link text (Markdown `[text](...)`) or image alt (`![alt](...)`). */
    text: string;
    /** Original href, kept for debugging / a11y. */
    raw: string;
}
export interface ParseVanceUriOptions {
    /** Display text from the Markdown token. */
    text: string;
    /** Whether the source was image syntax (`![]()`). */
    imageStyle: boolean;
}
export declare class VanceUriParseError extends Error {
    readonly href: string;
    constructor(message: string, href: string);
}
export declare function parseVanceUri(href: string, opts: ParseVanceUriOptions): EmbedRef;
/** True iff the href starts with the vance: scheme. */
export declare function isVanceUri(href: string | undefined | null): boolean;
//# sourceMappingURL=parseVanceUri.d.ts.map