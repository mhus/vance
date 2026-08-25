import { describe, it, expect } from 'vitest';

import { parseVanceUri, referrerDirOf, VanceUriParseError } from './parseVanceUri';

const opts = (referrerDir?: string) => ({ text: 'x', imageStyle: false, referrerDir });

/**
 * The scheme is a marker, not a mode — the slashes decide where a
 * reference lands, exactly as `DocumentRefResolver` does on the server.
 * These pin the three forms against each other, because the failure mode
 * is silent: a reference that resolves to the wrong document renders
 * something, and it looks like success.
 */
describe('parseVanceUri — where a reference lands', () => {
  it('resolves the rootless form against the referrer folder', () => {
    const ref = parseVanceUri('vance:analysis.yaml', opts('_ext/demo'));

    expect(ref.path).toBe('_ext/demo/analysis.yaml');
    expect(ref.project).toBeUndefined();
  });

  it('resolves the rootless form at the root when no document is declared', () => {
    // Chat, inbox, search hits: the text belongs to no document, so there
    // is nothing for a relative reference to be relative to.
    expect(parseVanceUri('vance:analysis.yaml', opts()).path).toBe('analysis.yaml');
  });

  it('ignores the referrer for the rooted form', () => {
    expect(parseVanceUri('vance:/a/b.yaml', opts('_ext/demo')).path).toBe('a/b.yaml');
  });

  it('ignores the referrer for a cross-project reference', () => {
    // Its base is the other project's root — a folder in this project has
    // no meaning over there.
    const ref = parseVanceUri('vance://other/a/b.yaml', opts('_ext/demo'));

    expect(ref.project).toBe('other');
    expect(ref.path).toBe('a/b.yaml');
  });

  it('collapses .. against the referrer folder', () => {
    expect(parseVanceUri('vance:../sibling/x.md', opts('docs/sub')).path)
      .toBe('docs/sibling/x.md');
  });

  it('refuses a reference that climbs above the project root', () => {
    // Clamping would hand back a different document without saying so.
    expect(() => parseVanceUri('vance:../../x.md', opts('docs'))).toThrow(VanceUriParseError);
  });

  it('keeps the query on the relative form', () => {
    const ref = parseVanceUri('vance:analysis.yaml?kind=chart', opts('_ext/demo'));

    expect(ref.path).toBe('_ext/demo/analysis.yaml');
    expect(ref.kindHint).toBe('chart');
  });
});

/**
 * Two namespaces share one query string: the reference grammar's own words
 * and the read parameters of a parameterised view. The split has to match
 * the server's `MountQuery.RESERVED` — a word kept on the wrong side either
 * reaches a source that never asked for it, or is lost on the way there.
 */
describe('parseVanceUri — what belongs to the source', () => {
  it('carries the foreign parameters as written', () => {
    const ref = parseVanceUri('vance:analysis.yaml?from=2026-02-01&to=2026-03-31', opts('d'));

    expect(ref.viewQuery).toBe('from=2026-02-01&to=2026-03-31');
  });

  it('keeps our own words out of it', () => {
    const ref = parseVanceUri('vance:/a.yaml?kind=chart&mode=preview&from=1', opts());

    expect(ref.viewQuery).toBe('from=1');
    expect(ref.kindHint).toBe('chart');
  });

  it('is undefined when the whole query was ours', () => {
    // Undefined, not '': everything downstream reads it as "a plain read",
    // and an empty string would take the parameterised branch.
    expect(parseVanceUri('vance:/a.yaml?kind=chart', opts()).viewQuery).toBeUndefined();
    expect(parseVanceUri('vance:/a.yaml', opts()).viewQuery).toBeUndefined();
  });

  it('never passes a query token to a source', () => {
    // `?token=` is how a browser authenticates a content URL that cannot
    // carry a header. It is a credential, not a read parameter.
    const ref = parseVanceUri('vance:/a.yaml?token=eyJhbGciOiJIUzI1NiJ9.x.y&from=1', opts());

    expect(ref.viewQuery).toBe('from=1');
  });

  it('does not re-encode what it passes on', () => {
    // Re-serialising would collapse `a=1&b=2` into one opaque parameter.
    const ref = parseVanceUri('vance:/a.yaml?q=a%20b&n=1', opts());

    expect(ref.viewQuery).toBe('q=a%20b&n=1');
  });
});

describe('referrerDirOf', () => {
  it('drops the file name', () => {
    expect(referrerDirOf('_ext/demo/report.md')).toBe('_ext/demo');
  });

  it('is empty for a document at the project root', () => {
    expect(referrerDirOf('report.md')).toBe('');
  });

  it('is empty for no document at all', () => {
    expect(referrerDirOf(null)).toBe('');
    expect(referrerDirOf(undefined)).toBe('');
  });

  it('tolerates a leading slash', () => {
    expect(referrerDirOf('/a/b.md')).toBe('a');
  });
});
