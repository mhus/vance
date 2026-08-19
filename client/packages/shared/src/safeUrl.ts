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
