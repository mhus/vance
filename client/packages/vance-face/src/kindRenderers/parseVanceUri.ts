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

export class VanceUriParseError extends Error {
  constructor(message: string, public readonly href: string) {
    super(message);
    this.name = 'VanceUriParseError';
  }
}

/**
 * File-extension → render-kind. Lets `![alt](images/foo.png)` and
 * `[clip](audio/bar.mp3)` render through the matching media view
 * even when the LLM omits the `?kind=` hint and the resolved
 * Document carries a generic kind. PDF is intentionally excluded —
 * embedded PDFs are too large for a chat stream and should stay
 * link-only.
 */
const EXTENSION_KIND_MAP: Readonly<Record<string, string>> = Object.freeze({
  // Raster images
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image',
  webp: 'image', bmp: 'image', avif: 'image', ico: 'image', heic: 'image',
  // Vector images — share ImageView via the `svg` registry entry
  svg: 'svg',
  // Audio
  mp3: 'audio', wav: 'audio', ogg: 'audio', oga: 'audio',
  m4a: 'audio', flac: 'audio', aac: 'audio',
  // Video
  mp4: 'video', webm: 'video', mov: 'video', m4v: 'video', ogv: 'video',
});

function inferKindFromPath(path: string): string | undefined {
  const dot = path.lastIndexOf('.');
  if (dot < 0 || dot === path.length - 1) return undefined;
  const ext = path.slice(dot + 1).toLowerCase();
  return EXTENSION_KIND_MAP[ext];
}

export function parseVanceUri(href: string, opts: ParseVanceUriOptions): EmbedRef {
  // URL constructor accepts custom schemes. We give it a base only
  // if needed; for `vance:` it works out of the box because the
  // scheme is opaque-style with optional authority.
  let url: URL;
  try {
    url = new URL(href);
  } catch (e) {
    throw new VanceUriParseError('Invalid URI', href);
  }
  if (url.protocol !== 'vance:') {
    throw new VanceUriParseError(`Expected vance: scheme, got ${url.protocol}`, href);
  }

  const project = url.hostname ? decodeURIComponent(url.hostname) : undefined;
  // url.pathname starts with '/' for both forms; strip the leading slash.
  const rawPath = url.pathname.replace(/^\//, '');
  const path = decodeURIComponent(rawPath);

  const explicitKind = url.searchParams.get('kind') ?? undefined;
  const kindHint = explicitKind ?? inferKindFromPath(path);
  const modeParam = url.searchParams.get('mode');
  const caption = url.searchParams.get('caption') ?? undefined;

  const mode: 'preview' | 'reference' = modeParam === 'preview' || modeParam === 'reference'
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
export function isVanceUri(href: string | undefined | null): boolean {
  return typeof href === 'string' && href.startsWith('vance:');
}
