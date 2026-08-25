import { describe, expect, it } from 'vitest';
import { ALLOWED_URI_REGEXP } from './sanitizeHtml';

/**
 * The **URI allow-list**, which is the part of the sanitiser configuration that
 * can be tested here — and the part most likely to rot, because it is a
 * hand-maintained copy of DOMPurify's default with `vance:` appended (there is
 * no programmatic way to extend the default).
 *
 * <p>`DOMPurify.sanitize` itself needs a DOM and this runner has none
 * (`environment: 'node'`, and neither jsdom nor happy-dom is in the workspace).
 * Adding one as a dependency for a single test is the wrong trade, so what the
 * sanitiser removes is verified in the browser instead — see the manual section
 * in `content.md`, whose table is a measurement.
 */
describe('ALLOWED_URI_REGEXP', () => {

  it('admits the schemes a document legitimately links to', () => {
    for (const uri of [
      'https://example.com', 'http://example.com', 'ftp://x', 'mailto:a@b',
      'tel:+49', 'callto:x', 'sms:123', 'cid:x', 'xmpp:a@b',
    ]) {
      expect(ALLOWED_URI_REGEXP.test(uri), uri).toBe(true);
    }
  });

  it('admits `vance:`, which is the reason it is extended at all', () => {
    // Without this the href is stripped and an inline document link renders as
    // an unclickable anchor.
    expect(ALLOWED_URI_REGEXP.test('vance:/documents/x.md')).toBe(true);
    expect(ALLOWED_URI_REGEXP.test('vance://other/x.md?kind=records')).toBe(true);
  });

  it('rejects the scripting schemes', () => {
    for (const uri of ['javascript:alert(1)', 'JavaScript:alert(1)', 'data:text/html,x',
                       'vbscript:x', 'evil:x']) {
      expect(ALLOWED_URI_REGEXP.test(uri), uri).toBe(false);
    }
  });

  it('admits relative and fragment references, which carry no scheme', () => {
    for (const uri of ['/docs/x.md', './x.md', '../x.md', '#section', 'x.md', '?q=1']) {
      expect(ALLOWED_URI_REGEXP.test(uri), uri).toBe(true);
    }
  });

  it('rejects a relative path whose first segment holds a colon', () => {
    // Measured, and the opposite of what I first assumed: `notes:2026/x.md` is
    // refused, because a colon before the first slash is indistinguishable from
    // a scheme and the conservative reading wins. Worth pinning because the
    // symptom — a link to a file with a colon in its name silently losing its
    // href — is otherwise very hard to attribute.
    expect(ALLOWED_URI_REGEXP.test('notes:2026/x.md')).toBe(false);
    // The way out is to make it visibly relative.
    expect(ALLOWED_URI_REGEXP.test('./notes:2026/x.md')).toBe(true);
  });
});
