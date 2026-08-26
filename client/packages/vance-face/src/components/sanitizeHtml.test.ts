import { describe, expect, it } from 'vitest';
import { ALLOWED_URI_REGEXP, SANITIZE_CONFIG } from './sanitizeHtml';

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
describe('SANITIZE_CONFIG', () => {

  it('forbids form and the submit-action attributes', () => {
    // The one exfiltration path a sanitised fragment had: DOMPurify's html
    // profile keeps <form> WITH its action, so a fake password prompt could
    // post to another host on a single click — no script involved. Measured in
    // the browser, then closed here. Pinned as config rather than behaviour
    // because DOMPurify.sanitize needs a DOM this runner does not have.
    expect(SANITIZE_CONFIG.FORBID_TAGS).toContain('form');
    for (const attr of ['action', 'formaction']) {
      expect(SANITIZE_CONFIG.FORBID_ATTR).toContain(attr);
    }
  });

  it('does not forbid target, which keeps the workspace alive', () => {
    // `target` was in the list with the two action attributes, and it took the
    // renderer's own external links with it: MarkdownView emits
    // target="_blank" on every http(s) link precisely so that following one
    // does not navigate the workspace away — killing the WS singleton, the open
    // Cortex tabs and any unsaved edit. Forbidding `form` closes the submit
    // vector; `target` on an <a> was never part of it.
    expect(SANITIZE_CONFIG.FORBID_ATTR).not.toContain('target');
  });

  it('does not forbid input or button', () => {
    // Without a form they are inert decoration — Enter submits nothing — and a
    // document may legitimately show what a field looks like.
    expect(SANITIZE_CONFIG.FORBID_TAGS).not.toContain('input');
    expect(SANITIZE_CONFIG.FORBID_TAGS).not.toContain('button');
  });
});

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
