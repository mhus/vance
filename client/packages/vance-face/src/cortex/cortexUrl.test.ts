import { describe, it, expect } from 'vitest';

import {
  readCortexView,
  splitForeignParams,
  writeCortexView,
  type CortexView,
} from './cortexUrl';

function view(patch: Partial<CortexView> = {}): CortexView {
  return {
    open: [],
    doc: null,
    bind: 'auto',
    pinned: null,
    autoTarget: true,
    suggestions: true,
    entries: {},
    queries: {},
    ...patch,
  };
}

describe('cortexUrl — suggestions preference', () => {
  it('defaults to on when the param is absent', () => {
    expect(readCortexView('').suggestions).toBe(true);
    expect(readCortexView('project=p1').suggestions).toBe(true);
  });

  it('reads sg=0 as off, any other value as on', () => {
    expect(readCortexView('sg=0').suggestions).toBe(false);
    expect(readCortexView('sg=1').suggestions).toBe(true);
  });

  it('omits the param for the on default and writes sg=0 when off', () => {
    expect(writeCortexView('', view({ suggestions: true }))).not.toContain('sg');
    expect(writeCortexView('', view({ suggestions: false }))).toContain('sg=0');
  });

  it('round-trips the off state alongside the other params', () => {
    const qs = writeCortexView('project=p1', view({
      open: ['a', 'b'],
      doc: 'b',
      autoTarget: false,
      suggestions: false,
    }));
    const back = readCortexView(qs);
    expect(back.suggestions).toBe(false);
    expect(back.autoTarget).toBe(false);
    expect(back.doc).toBe('b');
    // unrelated params survive
    expect(qs).toContain('project=p1');
  });

  it('turning suggestions back on strips the param again', () => {
    const qs = writeCortexView('sg=0', view({ suggestions: true }));
    expect(qs).not.toContain('sg');
  });
});

describe('cortexUrl — per-tab app entries', () => {
  it('keeps two app tabs apart', () => {
    // The whole point: Workbook and Wiki both used a bare `?page=`, so the
    // second tab overwrote the first (planning/inter-links.md §5.2).
    const qs = writeCortexView('', view({
      open: ['wb1', 'wiki1'],
      doc: 'wb1',
      entries: { wb1: 'page-7', wiki1: 'ops/deploys' },
    }));
    const back = readCortexView(qs);

    expect(back.entries).toEqual({ wb1: 'page-7', wiki1: 'ops/deploys' });
  });

  it('round-trips a handle containing the separators', () => {
    const handle = 'a:b,c/d e';
    const qs = writeCortexView('', view({ open: ['t'], doc: 't', entries: { t: handle } }));

    expect(readCortexView(qs).entries.t).toBe(handle);
  });

  it('drops entries whose tab is not open', () => {
    // Closing a tab must not leave its sub-position in the address bar.
    const qs = writeCortexView('', view({
      open: ['a'],
      doc: 'a',
      entries: { a: 'p1', gone: 'p2' },
    }));

    expect(qs).not.toContain('gone');
    expect(readCortexView(qs).entries).toEqual({ a: 'p1' });
  });

  it('omits the param entirely when nothing has a sub-position', () => {
    expect(writeCortexView('', view({ open: ['a'], doc: 'a' }))).not.toContain('entry');
  });

  it('skips malformed pairs instead of failing the read', () => {
    // The URL is user-editable; one bad pair must not cost the open-tab set.
    const back = readCortexView('open=a,b&doc=a&entry=a:p1,junk,:x,b:%E0%A4%A');

    expect(back.open).toEqual(['a', 'b']);
    expect(back.entries).toEqual({ a: 'p1' });
  });

  it('serialises in open-tab order so the same view yields the same URL', () => {
    // EditorApp.syncUrl compares strings to avoid redundant history writes.
    const entries = { b: 'p2', a: 'p1' };
    const first = writeCortexView('', view({ open: ['a', 'b'], doc: 'a', entries }));
    const second = writeCortexView('', view({ open: ['a', 'b'], doc: 'a', entries: { a: 'p1', b: 'p2' } }));

    expect(first).toBe(second);
  });
});

describe('cortexUrl — per-tab read parameters', () => {
  it('round-trips a query with its own separators intact', () => {
    // `&` and `=` are the query's own grammar and must survive being one
    // value inside another query — that is what the encoding is for.
    const q = 'from=2026-02-01&to=2026-03-31';
    const qs = writeCortexView('', view({ open: ['t'], doc: 't', queries: { t: q } }));

    expect(readCortexView(qs).queries.t).toBe(q);
  });

  it('keeps two parameterised tabs apart', () => {
    const qs = writeCortexView('', view({
      open: ['a', 'b'],
      doc: 'a',
      queries: { a: 'from=1', b: 'from=2' },
    }));

    expect(readCortexView(qs).queries).toEqual({ a: 'from=1', b: 'from=2' });
  });

  it('drops a query whose tab is not open', () => {
    // Otherwise closing the tab would leave a window in the address bar
    // that reappears on the next open.
    const qs = writeCortexView('', view({
      open: ['a'],
      doc: 'a',
      queries: { a: 'from=1', gone: 'from=2' },
    }));

    expect(qs).not.toContain('gone');
    expect(readCortexView(qs).queries).toEqual({ a: 'from=1' });
  });

  it('omits the param entirely when no tab is parameterised', () => {
    expect(writeCortexView('', view({ open: ['a'], doc: 'a' }))).not.toMatch(/(^|&)q=/);
  });

  it('is independent of the entry param', () => {
    const qs = writeCortexView('', view({
      open: ['a'],
      doc: 'a',
      entries: { a: 'page-7' },
      queries: { a: 'from=1' },
    }));
    const back = readCortexView(qs);

    expect(back.entries).toEqual({ a: 'page-7' });
    expect(back.queries).toEqual({ a: 'from=1' });
  });
});

describe('cortexUrl — splitForeignParams', () => {
  it('separates what Cortex owns from what it does not', () => {
    // The case it exists for: `?path=a.yaml?from=1&to=2` — the browser ends
    // the path param at the `&`, so `to` arrives as a param of its own and
    // the read parameters would otherwise be cut in half.
    const { known, foreign } = splitForeignParams('?project=p&path=a.yaml?from=1&to=2');

    expect(known).toBe('project=p&path=a.yaml?from=1');
    expect(foreign).toBe('to=2');
  });

  it('claims nothing when every param is ours', () => {
    const { known, foreign } = splitForeignParams('open=a,b&doc=a&entry=a:p&q=a:from%3D1&at=0');

    expect(foreign).toBe('');
    expect(known).toBe('open=a,b&doc=a&entry=a:p&q=a:from%3D1&at=0');
  });

  it('keeps both halves percent-encoded as written', () => {
    // Handed on, never re-serialised: re-encoding turns one parameter into
    // a different one.
    expect(splitForeignParams('label=a%20b').foreign).toBe('label=a%20b');
  });

  it('keeps a valueless foreign flag', () => {
    expect(splitForeignParams('project=p&refresh').foreign).toBe('refresh');
  });
});
