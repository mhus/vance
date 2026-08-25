import { describe, expect, it, vi } from 'vitest';
import { loadLibraries, stubVance } from './testing/libraryHarness';

/**
 * The harness itself, and the shape a *dependent* library is tested in.
 *
 * <p>`core1.test.ts` covers the one bundled library. This file covers the case
 * that made the question worth asking: a library that requires another one. The
 * runtime resolves `@require` on the server and evaluates the result as one
 * joined script — so a test that loads the dependency first and the library
 * second is testing the same arrangement, and a library that only works because
 * of an `import` would fail here.
 */
describe('loadLibraries', () => {

  it('evaluates a dependent library against the real core@1', () => {
    // A project library, as a test would carry it: source in, object out. The
    // point is that `core` — a top-level `const` in another file — is visible.
    const { db } = loadLibraries({
      sources: [
        { library: 'core@1' },
        {
          code: `
            const db = {
              total: function (rows, field) {
                return core.num(rows.reduce(function (s, r) {
                  return s + (Number(r[field]) || 0);
                }, 0));
              },
              newest: function (rows) {
                return core.sort(rows, 'date', true)[0];
              },
            };
          `,
        },
      ],
      expose: ['db'],
      vance: stubVance(),
    }) as { db: { total: (r: unknown[], f: string) => string; newest: (r: unknown[]) => unknown } };

    expect(db.total([{ a: 1.5 }, { a: 2 }, { a: 'x' }], 'a')).toBe('3.50');
    expect(db.newest([{ date: '2026-01-01' }, { date: '2026-08-01' }])).toEqual({
      date: '2026-08-01',
    });
  });

  it('hands the same `vance` to every library, so a call can be observed', async () => {
    const notify = vi.fn();
    const { db } = loadLibraries({
      sources: [
        { library: 'core@1' },
        { code: 'const db = { report: async function (t) { await core.say(t); } };' },
      ],
      expose: ['db'],
      vance: stubVance({ ui: { notify } }),
    }) as { db: { report: (t: string) => Promise<void> } };

    await db.report('done');
    expect(notify).toHaveBeenCalledWith('done');
  });

  it('reports the dependency order rather than the symptom', () => {
    // The dependency second: `core` is not yet declared when `db` runs. A test
    // that got a bare "core is not defined" would send the reader looking at the
    // library; naming the load list points at the order, which is the bug.
    expect(() =>
      loadLibraries({
        sources: [
          { code: 'const db = { n: core.num(1) };' },
          { library: 'core@1' },
        ],
        expose: ['db'],
        vance: stubVance(),
      }),
    ).toThrow(/Loading \[<inline>, core@1\] failed/);
  });

  it('says where it looked when a library is not there', () => {
    expect(() =>
      loadLibraries({ sources: [{ library: 'ghost@3' }], expose: ['ghost'], vance: stubVance() }),
    ).toThrow(/No bundled library 'ghost@3'.*app-libs\/ghost@3\.js/s);
  });
});
