import { describe, expect, it } from 'vitest';
import { isSafeUrl, safeUrl } from './safeUrl';

/**
 * Mirrors `SafeLinkTest` on the server side. The two guards are twins, so the
 * cases are deliberately the same ones: a case that only one of them covers is
 * how the two drifted apart the first time.
 *
 * Control characters are written as escapes on purpose — a literal one in the
 * source makes git treat the whole file as binary and the diff disappears from
 * review.
 */
describe('safeUrl', () => {
  it.each([
    'https://example.com/hit',
    'http://intranet.local/page', // internal is fine — nobody fetches it here
    'https://example.com:8443/a?b=c#d',
    'mailto:ford@example.com',
  ])('passes an allowed scheme: %s', (url) => {
    expect(safeUrl(url)).toBe(url);
  });

  it.each([
    'javascript:alert(1)',
    'JavaScript:alert(1)', // scheme match is case-insensitive
    'data:text/html,<script>x</script>', // the same problem in a different coat
    'vbscript:msgbox',
    'file:///etc/passwd',
    '/relative/path', // would resolve against our own origin
    '//example.com/protocol-relative', // no scheme = relative
    'example.com/no-scheme',
    ':no-scheme-name',
    '   ',
  ])('refuses everything else: %j', (url) => {
    expect(safeUrl(url)).toBeNull();
  });

  it.each([
    'https://example.com/search?q=a b', // space
    'https://example.com/100%discount', // '%' without a hex pair
    'https://example.com/a|b',
    'https://example.com/a[1].pdf',
    'https://example.com/x#frag ment',
    'https://exa_mple.com/x', // underscore host
    'https://例え.jp/', // IDN, not punycode
  ])('accepts what the browser accepts: %s', (url) => {
    // These are the URLs the server-side twin used to reject, because
    // `java.net.URI` follows RFC 2396. Refusing them buys no safety and tells
    // the user the wrong thing about a link that is perfectly ordinary.
    expect(safeUrl(url)).toBe(url);
  });

  it.each([
    ['tab inside the scheme', 'java\tscript:alert(1)'],
    ['newline inside the scheme', 'java\nscript:alert(1)'],
    ['carriage return inside the scheme', 'java\rscript:alert(1)'],
    ['tab breaking up an allowed scheme', 'htt\tps://example.com/x'],
    ['newline inside the host', 'https://exa\nmple.com/x'],
    ['NUL inside the path', 'https://example.com/a\u0000b'],
    ['DEL inside the path', 'https://example.com/a\u007Fb'],
  ])('refuses a URL that reads as one thing and resolves as another (%s)', (_case, url) => {
    // The WHATWG parser *deletes* tab, CR and LF before parsing, so the string
    // navigates somewhere its own text does not name. Refuse rather than
    // silently normalise: the caller hides the link instead of rendering one
    // whose text lies about its target.
    expect(safeUrl(url)).toBeNull();
  });

  it('refuses an NBSP-prefixed scheme rather than reading past it', () => {
    // NBSP is whitespace to `String.trim()`, so the prefix comes off and the
    // scheme underneath is judged on its own — which is where it fails. (The
    // Java twin gets there differently: `trim()` leaves NBSP alone, and the
    // anchored scheme pattern then does not match.)
    expect(safeUrl('\u00A0javascript:alert(1)')).toBeNull();
  });

  it('refuses null and blank without throwing', () => {
    expect(safeUrl(null)).toBeNull();
    expect(safeUrl(undefined)).toBeNull();
    expect(safeUrl('')).toBeNull();
  });

  it('trims surrounding whitespace', () => {
    expect(safeUrl('  https://example.com  ')).toBe('https://example.com');
  });
});

describe('isSafeUrl', () => {
  it('answers the same question as safeUrl', () => {
    expect(isSafeUrl('https://example.com')).toBe(true);
    expect(isSafeUrl('javascript:alert(1)')).toBe(false);
    expect(isSafeUrl('https://exa\nmple.com/x')).toBe(false);
    expect(isSafeUrl(null)).toBe(false);
  });
});
