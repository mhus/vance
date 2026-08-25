import DOMPurify from 'dompurify';

/**
 * The one HTML allow-list in the web UI.
 *
 * <p>Extracted from `MarkdownView` when the `html` widget arrived: two copies of
 * a sanitiser configuration are two security decisions that drift apart, and the
 * one that drifts is always the one nobody is looking at. Both renderers import
 * from here, so widening or narrowing happens once.
 *
 * <p>`USE_PROFILES: { html, mathMl }` is DOMPurify's HTML profile: ordinary
 * markup and the `style` **attribute** survive; `<script>`, every `on*`
 * handler, `<iframe>` and SVG do not.
 */

// DOMPurify's default URI allowlist (http/https/mailto/tel/cid/xmpp/…) strips
// the href off any other scheme. Inline `vance:` links — `[Doc](vance:/x.md)` —
// would render as anchors without an href and be unclickable. We extend the
// regex with `vance:` so the attribute survives sanitisation; the click
// delegation in the renderer then routes navigation client-side.
//
// The leading `(?:f|ht)tps?|mailto|…|vance` block mirrors DOMPurify's own
// default — keep it in sync if upstream changes (there is no programmatic way
// to "append a scheme to the default allowlist").
export const ALLOWED_URI_REGEXP =
  /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|vance):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;

export const SANITIZE_CONFIG = {
  USE_PROFILES: { html: true, mathMl: true },
  ALLOWED_URI_REGEXP,
} as const;

/** Sanitise a fragment of untrusted HTML for insertion into the page. */
export function sanitizeHtml(raw: string): string {
  return DOMPurify.sanitize(raw, SANITIZE_CONFIG);
}
