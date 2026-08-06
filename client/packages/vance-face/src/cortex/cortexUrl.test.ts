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
