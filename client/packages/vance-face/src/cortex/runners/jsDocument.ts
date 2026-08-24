import type { RunnerDocument } from '@vance/runner-registry';

/**
 * Two orthogonal questions about a JavaScript document, kept apart on
 * purpose:
 *
 * <ul>
 *   <li>{@link isJsDocument} — "is this JavaScript?" A language fact,
 *       derived from mime / extension. Drives the language-level
 *       toolbar actions (Validate, Slart Generate / Update) and the
 *       help-panel topic. True for frontend and backend scripts
 *       alike.</li>
 *   <li>{@link hasServerTag} — "may the brain execute this?" A
 *       capability the author declares with a bare {@code @server}
 *       line in the script header. Without it the document is a
 *       frontend script and server-side Run is not offered.</li>
 * </ul>
 *
 * Before the split, "runnable on the server" was inferred from the
 * file extension — which stopped working once frontend JS got edited
 * in the same editor.
 */

const JS_MIMES = new Set([
  'text/javascript',
  'application/javascript',
  'application/x-javascript',
  'application/x-mjs',
  'text/x-javascript',
]);

const JS_EXTS = ['.js', '.mjs', '.mjsh'];

/** Language check: mime first, extension as the fallback. */
export function isJsDocument(doc: RunnerDocument): boolean {
  const m = (doc.mimeType ?? '').toLowerCase().trim();
  if (m && JS_MIMES.has(m)) return true;
  const p = doc.path.toLowerCase();
  return JS_EXTS.some((ext) => p.endsWith(ext));
}

/**
 * First JSDoc block of the source — the header. Mirrors the brain's
 * {@code ScriptHeaderParser} block pattern so both sides agree on
 * which comment counts as the header; a later {@code @server} in
 * regular documentation must not arm the Run button.
 */
const HEADER_BLOCK = /^\s*\/\*\*([\s\S]*?)\*\//;

/** One header line, JSDoc continuation star stripped. */
const CONTINUATION_STAR = /^\s*\*\s?/;

/**
 * {@code true} when the header declares {@code @server}.
 *
 * <p>The tag is a bare flag — a value is neither required nor read
 * ({@code @server} and {@code @server true} both count). The brain's
 * header parser only matches tags that carry a value, so a bare line
 * passes it silently: this is a Cortex-side marker, not a runtime
 * parameter.
 *
 * @param source the raw document body; {@code null} while the tab is
 *               still loading, which reads as "not declared" until
 *               the content arrives.
 */
export function hasServerTag(source: string | null | undefined): boolean {
  if (!source) return false;
  const block = HEADER_BLOCK.exec(source);
  if (!block) return false;
  return block[1]
    .split(/\r?\n/)
    .some((line) => /^\s*@server\b/.test(line.replace(CONTINUATION_STAR, '')));
}
