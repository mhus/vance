import { describe, expect, it } from 'vitest';
import { compareCells, compareInDirection } from './compare';

/**
 * The bug this file was extracted for: written as `factor * compare(a, b)`, a
 * descending sort also reverses the empty-last rule and puts every blank at the
 * top — under a comment claiming it does not. It shipped that way in the
 * `table` widget and was found by the `core@1` test, not by reading the code.
 */
describe('compareInDirection', () => {
  it('sorts present values in the direction asked for', () => {
    expect(compareInDirection(1, 2, false)).toBeLessThan(0);
    expect(compareInDirection(1, 2, true)).toBeGreaterThan(0);
  });

  it('keeps empty last whichever way the reader sorted', () => {
    for (const descending of [false, true]) {
      expect(compareInDirection('', 2, descending)).toBeGreaterThan(0);
      expect(compareInDirection(2, '', descending)).toBeLessThan(0);
      expect(compareInDirection(null, undefined, descending)).toBe(0);
    }
  });
});

describe('compareCells', () => {
  it('is numeric when both values are numbers', () => {
    // Lexicographically '146' < '68', so this is the whole point.
    expect(compareCells('68', '146')).toBeLessThan(0);
  });

  it('falls back to text for a mixed column', () => {
    expect(compareCells('abc', 'abd')).toBeLessThan(0);
  });
});
