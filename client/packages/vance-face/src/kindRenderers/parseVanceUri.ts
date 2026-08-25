/**
 * `vance:` URI parser — converts a Markdown link/image href into an
 * `EmbedRef` suitable for the embedded-channel renderer.
 *
 * Spec: specification/inline-and-embedded-content.md §3.1 / §3.2 /
 * §11.7.
 *
 * URI forms — the scheme is a marker, not a mode: what follows it
 * decides where the reference lands, exactly as on the server
 * ({@code DocumentRefResolver}).
 *
 *   vance:<path>?kind=<kind>                   relative to the referrer folder
 *   vance:/<path>?kind=<kind>                  current project, from its root
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
  /**
   * `?entry=` query param when present: a place *inside* the addressed
   * document, for an application manifest — the workbook page, the board
   * column. Opaque here; only the app that produced it knows what it means
   * (see planning/inter-links.md §1). Passed through untouched so a stale
   * handle can degrade inside the app instead of failing the parse.
   */
  entry?: string;
  /** Effective render mode after applying defaults + overrides. */
  mode: 'preview' | 'reference';
  /** `?caption=` query param when present. */
  caption?: string;
  /**
   * Everything in the query that is *not* ours — the read parameters of a
   * parameterised view (`?from=…&to=…` on a mounted document), passed on
   * as written and undefined when there is nothing left over.
   *
   * Kept because dropping it is the failure with no symptom: the base
   * document renders, and it looks like the link worked. Mirrors
   * {@code MountQuery.forward} on the server, down to the reserved list.
   */
  viewQuery?: string;
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
  /**
   * Folder of the document this reference was authored in — the base a
   * relative form resolves against. Omitted (chat, inbox, any surface
   * whose text belongs to no document) means the project root, which is
   * also what an empty string means.
   *
   * A *folder*, not the document: the caller strips the file name (see
   * {@code referrerDirOf}), because that is what the reference is
   * relative to.
   */
  referrerDir?: string;
}

export class VanceUriParseError extends Error {
  constructor(message: string, public readonly href: string) {
    super(message);
    this.name = 'VanceUriParseError';
  }
}

/**
 * File-extension → render-kind. Lets `![alt](images/foo.png)`,
 * `[clip](audio/bar.mp3)` and `[paper](docs/foo.pdf)` route to the
 * matching media view even when the LLM omits the `?kind=` hint and
 * the resolved Document carries a generic kind. PDF routes to a
 * button-triggered overlay rather than an inline render (chat stream
 * stays compact) — that's handled inside {@code PdfView}, the kind
 * mapping just gets the right adapter wired up.
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
  // PDF — button-only embedded view, full doc in a lightbox overlay
  pdf: 'pdf',
});

function inferKindFromPath(path: string): string | undefined {
  const dot = path.lastIndexOf('.');
  if (dot < 0 || dot === path.length - 1) return undefined;
  const ext = path.slice(dot + 1).toLowerCase();
  return EXTENSION_KIND_MAP[ext];
}

/**
 * Query parameters that belong to Vancetope and never travel to a source.
 * The reference grammar's own words (`document-refs.md` §1.1) plus the
 * content endpoint's disposition switch — the same set the server keeps in
 * {@code MountQuery.RESERVED}. Two namespaces share one query string, so
 * the split has to be made identically on both sides or a source receives
 * a word that was never meant for it.
 */
const RESERVED_QUERY_PARAMS = new Set(['kind', 'entry', 'mode', 'caption', 'download']);

/**
 * The part of a query a source would receive, or undefined when nothing is
 * left. Filtered, never re-serialised: the string is already percent-encoded
 * and re-encoding would turn `a=1&b=2` into one opaque parameter.
 */
function forwardQuery(search: string): string | undefined {
  const raw = search.replace(/^\?/, '');
  if (!raw) return undefined;
  const kept = raw
    .split('&')
    .filter((pair) => pair !== '')
    .filter((pair) => {
      const eq = pair.indexOf('=');
      const key = (eq < 0 ? pair : pair.slice(0, eq)).toLowerCase();
      return !RESERVED_QUERY_PARAMS.has(key);
    });
  return kept.length > 0 ? kept.join('&') : undefined;
}

/**
 * The folder a document's references resolve against: the document path
 * minus its file name. A path without a slash sits at the project root,
 * whose folder is the empty string.
 */
export function referrerDirOf(documentPath: string | null | undefined): string {
  const path = (documentPath ?? '').replace(/^\/+/, '');
  const slash = path.lastIndexOf('/');
  return slash < 0 ? '' : path.slice(0, slash);
}

/**
 * Collapses `.` / `..` and empty segments, mirroring the server's
 * {@code DocumentRefResolver.canonicalize}. A `..` that would climb above
 * the project root throws rather than clamping: the caller asked for a
 * document outside the project, and silently handing back a different one
 * is the failure mode nobody can see.
 */
function canonicalize(path: string, href: string): string {
  const out: string[] = [];
  for (const seg of path.split('/')) {
    if (seg === '' || seg === '.') continue;
    if (seg === '..') {
      if (out.length === 0) {
        throw new VanceUriParseError('Reference escapes above the project root', href);
      }
      out.pop();
      continue;
    }
    out.push(seg);
  }
  return out.join('/');
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
  // The leading slash is the whole signal: `vance:/a` and `vance://p/a`
  // arrive with `pathname === '/a'`, `vance:a` with `pathname === 'a'`.
  // Only the last one is relative, and only then does the referrer
  // folder come into play — a cross-project ref never does, because its
  // base is the other project's root.
  const rawPath = url.pathname;
  const absolute = rawPath.startsWith('/') || !!project;
  // Decode before merging, never after: the referrer folder is already a
  // plain path, and decoding it a second time would corrupt a name that
  // legitimately contains a percent sign.
  const decoded = decodeURIComponent(rawPath);
  const base = absolute ? '' : (opts.referrerDir ?? '');
  const path = canonicalize(base ? `${base}/${decoded}` : decoded, href);

  const explicitKind = url.searchParams.get('kind') ?? undefined;
  const inferredKind = inferKindFromPath(path);
  // {@code ?kind=document} is the LLM's catch-all fallback — it carries
  // no real type information, so let a known file-extension take over
  // when the path looks like media. Any other explicit kind
  // (mindmap, sheet, graph, …) still wins over inference because it
  // carries domain semantics the extension can't predict.
  const kindHint = explicitKind && explicitKind !== 'document'
    ? explicitKind
    : (inferredKind ?? explicitKind);
  const modeParam = url.searchParams.get('mode');
  const caption = url.searchParams.get('caption') ?? undefined;
  // Empty (`?entry=`) is treated as absent: it addresses no place, and
  // carrying `''` would make every consumer test for two falsy shapes.
  const entry = url.searchParams.get('entry') || undefined;

  const mode: 'preview' | 'reference' = modeParam === 'preview' || modeParam === 'reference'
    ? modeParam
    : (opts.imageStyle ? 'preview' : 'reference');

  return {
    path,
    project,
    kindHint: kindHint?.toLowerCase(),
    entry,
    mode,
    caption,
    viewQuery: forwardQuery(url.search),
    text: opts.text,
    raw: href,
  };
}

/** True iff the href starts with the vance: scheme. */
export function isVanceUri(href: string | undefined | null): boolean {
  return typeof href === 'string' && href.startsWith('vance:');
}
