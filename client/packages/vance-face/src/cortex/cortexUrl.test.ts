import { describe, it, expect } from 'vitest';

import { readCortexView, writeCortexView, type CortexView } from './cortexUrl';

function view(patch: Partial<CortexView> = {}): CortexView {
  return {
    open: [],
    doc: null,
    bind: 'auto',
    pinned: null,
    autoTarget: true,
    suggestions: true,
    entries: {},
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
