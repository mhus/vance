/**
 * Builder for a `vance:` document reference.
 *
 * The grammar is small but has two traps, and both bite silently. The
 * cross-project form needs a *double* slash (`vance://<project>/<path>`, the
 * project is a URI authority) — with one slash the project name becomes the
 * first path segment and the reference resolves to a document that does not
 * exist. And `?entry=` is app-owned text that may contain `&`, `#` or `=`, so
 * unencoded it swallows the rest of the query.
 *
 * Parsing lives in `parseVanceUri` (`@vance/face`); this is the producing half,
 * here because several pickers across addons need it.
 */

export interface VanceRef {
  /** Document path, without a leading slash. */
  path: string;
  /** Project name — omit for the current project. */
  project?: string | null;
  /** `?kind=` render hint. */
  kind?: string | null;
  /** `?entry=` — a place inside the document, for application manifests. */
  entry?: string | null;
}

/** `vance:/<path>` · `vance://<project>/<path>` · plus `?kind=`/`?entry=`. */
export function vanceRef(ref: VanceRef): string {
  const path = ref.path.replace(/^\/+/, '');
  const params = new URLSearchParams();
  if (ref.kind) params.set('kind', ref.kind);
  if (ref.entry) params.set('entry', ref.entry);
  const qs = params.toString();
  const base = ref.project
    ? `vance://${encodeURIComponent(ref.project)}/${path}`
    : `vance:/${path}`;
  return qs ? `${base}?${qs}` : base;
}
