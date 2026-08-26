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

/**
 * `<form>` and the submit-target attributes, removed.
 *
 * <p>Measured, not assumed: DOMPurify's html profile keeps `<form>` **with its
 * `action`**, so a sanitised fragment could render
 * `<form action="https://elsewhere/"><input type="password">` and post it on a
 * single click. No script needed, no handler, nothing the rest of the allow-list
 * would catch — and this renderer draws chat messages, documents and pages from
 * every source there is.
 *
 * <p>A form in a document has no legitimate function anyway: nothing submits it
 * usefully, and Vance's own forms are widgets, not markup. An `<input>` on its
 * own stays allowed — without a form it is inert decoration, and Enter does
 * nothing.
 *
 * <p>Both the tag and the attributes, so that a future DOMPurify default which
 * re-allows `form` cannot quietly re-open the exit.
 */
const FORBID_TAGS = ['form'];
const FORBID_ATTR = ['action', 'formaction', 'target'];

export const SANITIZE_CONFIG = {
  USE_PROFILES: { html: true, mathMl: true },
  ALLOWED_URI_REGEXP,
  FORBID_TAGS,
  FORBID_ATTR,
} as const;

/** Sanitise a fragment of untrusted HTML for insertion into the page. */
export function sanitizeHtml(raw: string): string {
  return DOMPurify.sanitize(raw, SANITIZE_CONFIG);
}
