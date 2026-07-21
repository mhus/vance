import { describe, it, expect } from 'vitest';
import { slugify, targetName } from './slug';

/**
 * Parity contract with the server-side Java `WikiFolderReader.slugify`
 * (vance-addon-brain-wiki). The client re-implements the same algorithm so the
 * synchronous red-link check matches the filename the server would generate;
 * if the two drift, wikilink resolution breaks *silently* (no crash, no type
 * error). This case table encodes the shared rule:
 *
 *   lower-case; keep a-z 0-9 _; collapse any run of other characters
 *   (incl. `-`, ` `, `/`, `.`, and anything non-ASCII/symbolic) to a single
 *   `-`; never emit a leading `-`; trim trailing `-`.
 *
 * When the Java side changes, update both it and this table in the same PR.
 */
describe('slugify — parity with Java WikiFolderReader.slugify', () => {
  const cases: Array<[string, string]> = [
    ['Deploy Guide', 'deploy-guide'],
    ['main', 'main'],
    ['Main', 'main'],
    ['Onboarding', 'onboarding'],
    ['eng/Deploy', 'eng-deploy'], // slash collapses to a dash
    ['Foo.Bar', 'foo-bar'], // dot collapses to a dash
    ['a__b', 'a__b'], // underscore is kept
    ['  Hello   World  ', 'hello-world'], // runs of spaces → single dash, trimmed
    ['C++ Notes', 'c-notes'], // symbol run → single dash
    ['---leading', 'leading'], // no leading dash
    ['trailing---', 'trailing'], // trailing dashes trimmed
    ['Release 2.0', 'release-2-0'],
    ['über uns', 'ber-uns'], // non-ASCII dropped (only a-z0-9_ kept)
    ['', ''],
    ['???', ''], // all-symbol → empty (never emits a leading dash)
  ];
  for (const [input, expected] of cases) {
    it(`${JSON.stringify(input)} → ${JSON.stringify(expected)}`, () => {
      expect(slugify(input)).toBe(expected);
    });
  }

  it('null / undefined → empty string', () => {
    expect(slugify(null)).toBe('');
    expect(slugify(undefined)).toBe('');
  });
});

describe('targetName — leaf of a [[Space/Name]] target', () => {
  it('bare name returns itself', () => {
    expect(targetName('Onboarding')).toBe('Onboarding');
  });
  it('space-qualified returns the part after the last slash', () => {
    expect(targetName('eng/Deploy')).toBe('Deploy');
    expect(targetName('a/b/c')).toBe('c');
  });
});

describe('the red-link check path (targetName → slugify)', () => {
  it('resolves a space-qualified target to its leaf slug', () => {
    expect(slugify(targetName('eng/Deploy Guide'))).toBe('deploy-guide');
  });
});
