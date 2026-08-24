import { describe, it, expect } from 'vitest';

// Reached by path, not through the `@vance/components` barrel: that barrel
// re-exports `.vue` files, and this suite runs as plain Node without the Vue
// plugin (see vitest.config.ts). The module itself is dependency-free TS.
import { vanceRef } from '../../../components/src/vanceUri';

import { parseVanceUri } from './parseVanceUri';

/**
 * Round trip rather than string assertions: `vanceRef` (in `@vance/components`,
 * used by every link picker) produces what `parseVanceUri` consumes, and the two
 * halves live in different packages. Pinning the producer against its own
 * expected string would let them drift apart while both tests stayed green.
 */
function roundTrip(ref: Parameters<typeof vanceRef>[0]) {
  return parseVanceUri(vanceRef(ref), { text: 'x', imageStyle: false });
}

describe('vanceRef → parseVanceUri', () => {
  it('keeps a same-project path relative', () => {
    const back = roundTrip({ path: 'apps/wiki/_app.yaml', kind: 'application' });

    expect(back.path).toBe('apps/wiki/_app.yaml');
    expect(back.project).toBeUndefined();
    expect(back.kindHint).toBe('application');
  });

  it('puts a foreign project in the authority, not the path', () => {
    // The trap: one slash makes the project the first path segment, and the
    // reference then resolves to a document that does not exist.
    const uri = vanceRef({ path: 'apps/wiki/_app.yaml', project: 'other' });
    expect(uri.startsWith('vance://other/')).toBe(true);

    const back = parseVanceUri(uri, { text: 'x', imageStyle: false });
    expect(back.project).toBe('other');
    expect(back.path).toBe('apps/wiki/_app.yaml');
  });

  it('round-trips an entry handle containing query metacharacters', () => {
    // Handles are app-owned text. Unencoded, `&` ends the entry early and the
    // rest becomes a separate param.
    const handle = 'ops/deploys?x=1&y=2#frag';
    const back = roundTrip({ path: 'a/_app.yaml', kind: 'application', entry: handle });

    expect(back.entry).toBe(handle);
  });

  it('omits the query entirely when there is neither kind nor entry', () => {
    expect(vanceRef({ path: 'a/b.md' })).toBe('vance:/a/b.md');
  });

  it('treats a null entry as absent', () => {
    expect(roundTrip({ path: 'a/_app.yaml', entry: null }).entry).toBeUndefined();
  });

  it('tolerates a leading slash in the path', () => {
    expect(roundTrip({ path: '/a/b.md' }).path).toBe('a/b.md');
  });
});
