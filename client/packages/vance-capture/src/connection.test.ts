import { describe, expect, it } from 'vitest';
import { cortexUrlFor, linksAppUrl, originOf } from './connection';
import type { ConnectionBlob } from '@vance/shared/integration-connection';

/**
 * Six lines that cost two rounds of debugging in Safari, so they get a test.
 *
 * The failure was invisible from the outside: a malformed pattern makes
 * `permissions.request` *throw*, the exception escaped a click handler as an
 * unhandled rejection, and the button simply did nothing.
 */
const blob = (brainUrl: string): ConnectionBlob => ({
  brainUrl,
  tenant: 'acme',
  projectId: 'reading',
  profiles: ['links-capture'],
  token: 'x',
});

describe('cortexUrlFor', () => {
  it('addresses a document by path, not by an id it cannot know', () => {
    expect(cortexUrlFor(blob('https://eddie.example'), 'web/post.md'))
      .toBe('https://eddie.example/cortex?project=reading&path=web%2Fpost.md');
  });

  it('encodes what the query would otherwise swallow', () => {
    expect(cortexUrlFor(blob('https://eddie.example'), 'web/a b&c.md'))
      .toContain('path=web%2Fa+b%26c.md');
  });
});

describe('linksAppUrl', () => {
  it('points at the manifest that IS the app', () => {
    expect(linksAppUrl({ ...blob('https://eddie.example'), target: 'links' }))
      .toBe('https://eddie.example/cortex?project=reading&path=links%2F_app.yaml');
  });

  it('tolerates slashes around the folder', () => {
    expect(linksAppUrl({ ...blob('https://eddie.example'), target: '/links/' }))
      .toContain('path=links%2F_app.yaml');
  });

  /** No folder, no list — and a button that cannot go anywhere is worse than none. */
  it('is null without a target folder', () => {
    expect(linksAppUrl(blob('https://eddie.example'))).toBeNull();
    expect(linksAppUrl({ ...blob('https://eddie.example'), target: '/' })).toBeNull();
  });
});

describe('originOf', () => {
  /**
   * The one that broke. A match pattern is `<scheme>://<host>/<path>` and has
   * no way to express a port — while `URL.origin`, the obvious thing to reach
   * for, includes one.
   */
  it('drops the port, because match patterns cannot carry one', () => {
    expect(originOf(blob('http://localhost:9901'))).toBe('http://localhost/*');
    expect(originOf(blob('https://eddie.example:8443/'))).toBe('https://eddie.example/*');
  });

  it('keeps the scheme', () => {
    expect(originOf(blob('https://eddie.example'))).toBe('https://eddie.example/*');
    expect(originOf(blob('http://eddie.example'))).toBe('http://eddie.example/*');
  });

  it('ignores a path — a pattern is about the host', () => {
    expect(originOf(blob('https://eddie.example/brain/acme'))).toBe('https://eddie.example/*');
  });

  /** Better no pattern than a malformed one: the caller answers "not granted". */
  it('returns null for something that is not a URL', () => {
    expect(originOf(blob('not a url'))).toBeNull();
    expect(originOf(blob(''))).toBeNull();
  });
});
