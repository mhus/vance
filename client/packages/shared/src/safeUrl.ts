/**
 * Guard for a URL that came from outside and is about to become an `href`.
 *
 * Foreign feed entries and search hits carry a `url` the far end wrote, and the
 * clients render it as a link. A `javascript:` URL there runs on the brain's own
 * origin as soon as somebody clicks the headline — so the check belongs where
 * every such link passes, not in each template that happens to remember it.
 *
 * Plattform-neutral and dependency-free, like the rest of `@vance/shared`: it
 * uses the WHATWG `URL` parser, which exists on web and in React Native.
 *
 * Client-side twin of `vance-shared/.../net/SafeLink.java`, which guards the
 * links the *server* hands to a human (mail body, inbox item). Same allow-list,
 * same refusal of control characters — two lists that drift apart are worse
 * than one marginal scheme neither of them needed. The two differ only in how
 * they reach the scheme: here the WHATWG parser is available and is what the
 * browser will use anyway, so it is the honest model; the JVM has no WHATWG
 * parser and `java.net.URI` is strictly harsher for reasons unrelated to
 * safety, so the Java side matches the scheme by regex instead.
 */

/**
 * Schemes a link may use.
 *
 * An allow-list, because the dangerous set is not enumerable — `javascript:`,
 * `data:` and `vbscript:` are the known ones and the next will arrive without
 * anyone editing this file. `mailto:` is here because a feed entry legitimately
 * links to an address; `data:` is not, because a `data:text/html` link is the
 * same problem wearing a different name.
 */
const ALLOWED_PROTOCOLS = new Set(['http:', 'https:', 'mailto:']);

/**
 * ASCII control characters (C0 plus DEL), which no real URL contains.
 *
 * The WHATWG parser *deletes* tab, CR and LF from its input before parsing, so
 * a string carrying one resolves to something other than what it displays —
 * `htt<TAB>ps://…` is shown as broken and navigates fine, `https://a.com/x<LF>y`
 * is shown on two lines and navigates to one path. That display/resolve
 * mismatch is exactly what a link guard exists to prevent, so such a string is
 * refused rather than silently normalised: the caller then hides the link
 * instead of rendering one whose text lies about its target.
 *
 * Built from a string literal on purpose — a literal control character in the
 * source makes git, grep and blame treat the whole file as binary.
 *
 * Kept in step with the server-side twin
 * `vance-shared/.../net/SafeLink.java`, which refuses the same set.
 */
const CONTROL_CHARS = new RegExp('[\\u0000-\\u001F\\u007F]');

/**
 * The URL if it is safe to link to, otherwise `null`.
 *
 * Returns `null` rather than a placeholder so the caller decides what to do with
 * an entry it cannot link — hiding the link keeps the title readable, which is
 * better than a link that goes nowhere.
 *
 * Relative URLs are rejected: every caller here handles absolute remote links,
 * and accepting a relative one would mean resolving it against our own origin,
 * which is exactly the confusion the guard exists to prevent.
 */
export function safeUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (CONTROL_CHARS.test(trimmed)) return null;
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    // Not absolute, or not a URL at all.
    return null;
  }
  return ALLOWED_PROTOCOLS.has(parsed.protocol.toLowerCase()) ? trimmed : null;
}

/** True when {@link safeUrl} would return a value. Reads better in a `v-if`. */
export function isSafeUrl(raw: string | null | undefined): boolean {
  return safeUrl(raw) !== null;
}
