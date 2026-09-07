import { describe, it, expect } from 'vitest';

import { readKindFromBody } from './documentHeaderCodec';

/**
 * The one field a client needs before the server has persisted it — a mounted
 * document's row carries no kind until something has read its body.
 */
describe('readKindFromBody', () => {
  it('reads $meta.kind from YAML', () => {
    const body = '$meta:\n  kind: chart\nchart:\n  chartType: line\n';

    expect(readKindFromBody(body, 'text/yaml')).toBe('chart');
  });

  it('reads $meta.kind from JSON', () => {
    expect(readKindFromBody('{"$meta": {"kind": "records"}, "rows": []}', 'application/json'))
      .toBe('records');
  });

  it('reads kind from markdown front matter', () => {
    expect(readKindFromBody('---\nkind: workpage\ntitle: x\n---\n\n# Hi', 'text/markdown'))
      .toBe('workpage');
  });

  it('ignores a top-level kind outside $meta', () => {
    // `kind:` as a field of the document says what the document is *about*,
    // not what it is. Reading it would dispatch a plain config to a renderer.
    expect(readKindFromBody('kind: chart\nvalue: 1\n', 'text/yaml')).toBeNull();
  });

  it('is null for a body that declares nothing', () => {
    expect(readKindFromBody('just prose', 'text/markdown')).toBeNull();
    expect(readKindFromBody('', 'text/yaml')).toBeNull();
    expect(readKindFromBody(null, 'text/yaml')).toBeNull();
  });

  it('is null for a mime that cannot carry a header', () => {
    expect(readKindFromBody('$meta:\n  kind: chart\n', 'application/pdf')).toBeNull();
  });

  it('is null for age armor — the dashes are not a front-matter fence', () => {
    // Regression for the age-encrypted document kind: an armored body
    // opens with `-----BEGIN AGE ENCRYPTED FILE-----`, which starts with
    // the front-matter dashes but is not a fence. A misparse here would
    // dispatch every age document to a wrong renderer. The kind of an
    // age document comes from its mime type, never from its body.
    const armor = [
      '-----BEGIN AGE ENCRYPTED FILE-----',
      'YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSB0QXVkQmNwZ3Z6YnNRZDJP',
      'WlFId3hyeFNmRS9SdUVUTkFhY1FXSno5VUFB',
      '-----END AGE ENCRYPTED FILE-----',
      '',
    ].join('\n');

    expect(readKindFromBody(armor, 'application/age+armored')).toBeNull();
    // Even a mislabelled age doc (wrong mime → markdown probe) must not
    // turn the armor into a header.
    expect(readKindFromBody(armor, 'text/markdown')).toBeNull();
  });

  it('tolerates quotes around the value', () => {
    expect(readKindFromBody('$meta:\n  kind: "chart"\n', 'text/yaml')).toBe('chart');
    expect(readKindFromBody("---\nkind: 'workpage'\n---\n", 'text/markdown')).toBe('workpage');
  });

  it('only looks at the head', () => {
    // A `$meta` block buried past the probe is not a header — the convention
    // puts it first, and scanning a whole document to prove otherwise is the
    // cost this bound exists to avoid.
    const body = `${'# filler\n'.repeat(2000)}$meta:\n  kind: chart\n`;

    expect(readKindFromBody(body, 'text/yaml')).toBeNull();
  });
});
