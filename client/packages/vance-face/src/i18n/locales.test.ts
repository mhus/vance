import { describe, expect, it } from 'vitest';
import { isUiLocale, pluralIndex } from './locales';

describe('pluralIndex', () => {
  it('picks one | few | many for Polish', () => {
    expect([1, 2, 4, 5, 12, 21, 22, 25, 0].map((n) => pluralIndex('pl', n, 3))).toEqual([
      0, 1, 1, 2, 2, 2, 1, 2, 2,
    ]);
  });

  it('picks one | few | many for Russian', () => {
    expect([1, 2, 5, 11, 21, 22, 25, 0].map((n) => pluralIndex('ru', n, 3))).toEqual([
      0, 1, 2, 2, 0, 1, 2, 2,
    ]);
  });

  it('falls back to the general form when no count was given', () => {
    expect(pluralIndex('pl', -1, 3)).toBe(2);
  });

  it("keeps English's rule for a two-variant message", () => {
    expect([1, 2, 5].map((n) => pluralIndex('pl', n, 2))).toEqual([0, 1, 1]);
  });
});

describe('isUiLocale', () => {
  it('knows the configured locales only', () => {
    expect(isUiLocale('ru')).toBe(true);
    expect(isUiLocale('it')).toBe(false);
  });
});
