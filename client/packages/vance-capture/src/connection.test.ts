import { describe, expect, it } from 'vitest';
import { originOf } from './connection';
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
