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
export class VanceUriParseError extends Error {
    href;
    constructor(message, href) {
        super(message);
        this.href = href;
        this.name = 'VanceUriParseError';
    }
}
export function parseVanceUri(href, opts) {
    // URL constructor accepts custom schemes. We give it a base only
    // if needed; for `vance:` it works out of the box because the
    // scheme is opaque-style with optional authority.
    let url;
    try {
        url = new URL(href);
    }
    catch (e) {
        throw new VanceUriParseError('Invalid URI', href);
    }
    if (url.protocol !== 'vance:') {
        throw new VanceUriParseError(`Expected vance: scheme, got ${url.protocol}`, href);
    }
    const project = url.hostname ? decodeURIComponent(url.hostname) : undefined;
    // url.pathname starts with '/' for both forms; strip the leading slash.
    const rawPath = url.pathname.replace(/^\//, '');
    const path = decodeURIComponent(rawPath);
    const kindHint = url.searchParams.get('kind') ?? undefined;
    const modeParam = url.searchParams.get('mode');
    const caption = url.searchParams.get('caption') ?? undefined;
    const mode = modeParam === 'preview' || modeParam === 'reference'
        ? modeParam
        : (opts.imageStyle ? 'preview' : 'reference');
    return {
        path,
        project,
        kindHint: kindHint?.toLowerCase(),
        mode,
        caption,
        text: opts.text,
        raw: href,
    };
}
/** True iff the href starts with the vance: scheme. */
export function isVanceUri(href) {
    return typeof href === 'string' && href.startsWith('vance:');
}
//# sourceMappingURL=parseVanceUri.js.map