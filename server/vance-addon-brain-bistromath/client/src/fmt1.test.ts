import { beforeEach, describe, expect, it } from 'vitest';
import { loadLibraries, stubVance } from './testing/libraryHarness';

/**
 * `fmt@1`. Every test pins a **locale**, because the library's whole subject is
 * that formatting is locale-dependent — a test that took the runner's default
 * would pass or fail depending on the machine.
 *
 * <p>`de-DE` and `en-US` are used deliberately: they disagree about the decimal
 * separator, which is where the interesting failures live.
 */
interface Fmt {
  locale: string;
  currency: string;
  byteBase: number;
  setup: (o: Record<string, unknown>) => Fmt;
  number: (v: unknown, d?: number) => string;
  money: (v: unknown, c?: string) => string;
  percent: (v: unknown, d?: number) => string;
  bytes: (v: unknown, d?: number) => string;
  parseNumber: (t: unknown, l?: string) => number | null;
  toNumber: (v: unknown) => number | null;
  date: (v: unknown, s?: string) => string;
  relative: (v: unknown, now?: unknown) => string;
  duration: (ms: unknown) => string;
  list: (i: unknown[], t?: string) => string;
  truncate: (t: unknown, n?: number) => string;
}

let fmt: Fmt;

beforeEach(() => {
  fmt = loadLibraries({
    sources: [{ library: 'fmt@1' }],
    expose: ['fmt'],
    vance: stubVance(),
  }).fmt as Fmt;
  fmt.setup({ locale: 'de-DE', currency: 'EUR' });
});

describe('numbers', () => {

  it('groups for the locale', () => {
    expect(fmt.number(1234.5, 2)).toBe('1.234,50');
    fmt.setup({ locale: 'en-US' });
    expect(fmt.number(1234.5, 2)).toBe('1,234.50');
  });

  it('is empty for what is not a number, including null and blank', () => {
    // A dash or a zero would both be claims the data did not make.
    for (const v of [null, undefined, '', 'abc', NaN]) {
      expect(fmt.number(v)).toBe('');
    }
  });

  it('keeps zero, which is a number', () => {
    expect(fmt.number(0, 2)).toBe('0,00');
    expect(fmt.toNumber(0)).toBe(0);
  });

  it('places the currency the way the locale does', () => {
    expect(fmt.money(9.5)).toMatch(/^9,50\s€$/);
    fmt.setup({ locale: 'en-US', currency: 'USD' });
    expect(fmt.money(9.5)).toBe('$9.50');
  });

  it('takes a currency per call over the default', () => {
    expect(fmt.money(1, 'CHF')).toContain('CHF');
  });
});

describe('percent', () => {

  it('takes a fraction, following Intl', () => {
    // 0.15 is 15 %. A library that read 15 as 15 % would disagree with every
    // other percent formatter the reader meets.
    // `\s`, not a literal space: de-DE separates with a narrow no-break space
    // (U+202F), and pinning an invisible character breaks on an ICU update.
    expect(fmt.percent(0.15)).toMatch(/^15\s%$/);
    expect(fmt.percent(0.1234, 1)).toMatch(/^12,3\s%$/);
  });
});

describe('bytes', () => {

  it('counts in 1024 with KB labels by default', () => {
    expect(fmt.bytes(0)).toBe('0 B');
    expect(fmt.bytes(1024)).toBe('1,0 KB');
    expect(fmt.bytes(1536)).toBe('1,5 KB');
    expect(fmt.bytes(1024 * 1024 * 3)).toBe('3,0 MB');
  });

  it('counts in 1000 when told to', () => {
    fmt.setup({ byteBase: 1000 });
    expect(fmt.bytes(1000)).toBe('1,0 KB');
  });

  it('keeps a sign', () => {
    expect(fmt.bytes(-2048)).toBe('-2,0 KB');
  });
});

describe('parseNumber', () => {

  it('reads the notation the reader typed', () => {
    // The case `Number()` gets wrong without complaining: in German this is a
    // thousand and a half, and `Number('1.234,56')` is NaN — but
    // `Number('1.234')` is 1.234, which is worse, because it looks fine.
    expect(fmt.parseNumber('1.234,56')).toBe(1234.56);
    expect(fmt.parseNumber('1.234')).toBe(1234);
    fmt.setup({ locale: 'en-US' });
    expect(fmt.parseNumber('1,234.56')).toBe(1234.56);
    expect(fmt.parseNumber('1,234')).toBe(1234);
  });

  it('takes a locale per call', () => {
    expect(fmt.parseNumber('1,5', 'de-DE')).toBe(1.5);
    expect(fmt.parseNumber('1,5', 'en-US')).toBe(15);
  });

  it('survives a pasted formatted number with odd spaces', () => {
    fmt.setup({ locale: 'fr-FR' });
    expect(fmt.parseNumber('1 234,56')).toBe(1234.56);
    expect(fmt.parseNumber('1 234,56')).toBe(1234.56);
  });

  it('is null for what is not a number, and 0 for zero', () => {
    for (const v of ['', '   ', 'abc', '1,2,3.4.5', null, undefined]) {
      expect(fmt.parseNumber(v)).toBeNull();
    }
    expect(fmt.parseNumber('0')).toBe(0);
    expect(fmt.parseNumber(-3)).toBe(-3);
  });

  it('keeps a sign and a bare decimal', () => {
    expect(fmt.parseNumber('-0,5')).toBe(-0.5);
    expect(fmt.parseNumber(',5')).toBe(0.5);
  });
});

describe('dates', () => {

  it('formats by style', () => {
    const d = new Date('2026-08-25T14:30:00Z');
    expect(fmt.date(d, 'iso')).toBe('2026-08-25');
    expect(fmt.date(d, 'short')).toBe('25.08.26');
    expect(fmt.date(d, 'long')).toContain('August');
  });

  it('returns an unparseable value unchanged', () => {
    // "Invalid Date" says nothing about what is in the field.
    expect(fmt.date('not a date')).toBe('not a date');
    expect(fmt.date(null)).toBe('');
  });

  it('names an unknown style instead of guessing one', () => {
    expect(() => fmt.date(new Date(), 'fancy')).toThrow(/no style 'fancy'/);
  });

  it('speaks relatively, against an injected now', () => {
    const now = new Date('2026-08-25T12:00:00Z');
    expect(fmt.relative(new Date('2026-08-22T12:00:00Z'), now)).toBe('vor 3 Tagen');
    expect(fmt.relative(new Date('2026-08-25T14:00:00Z'), now)).toBe('in 2 Stunden');
    expect(fmt.relative(new Date('2026-08-25T11:59:30Z'), now)).toContain('Sekunden');
  });
});

describe('duration', () => {

  it('is coarse on purpose', () => {
    expect(fmt.duration(0)).toBe('0 s');
    expect(fmt.duration(45_000)).toBe('45 s');
    expect(fmt.duration(3_600_000 * 2 + 300_000)).toBe('2 h 5 min');
    // Seconds are dropped once there are hours — nobody reads "2 h 5 min 3 s".
    expect(fmt.duration(3_600_000 * 2 + 3_000)).toBe('2 h');
  });
});

describe('text', () => {

  it('joins a list with the locale grammar', () => {
    expect(fmt.list(['a', 'b', 'c'])).toBe('a, b und c');
    expect(fmt.list([])).toBe('');
    expect(fmt.list(['a'])).toBe('a');
  });

  it('truncates at a word boundary', () => {
    expect(fmt.truncate('one two three four', 12)).toBe('one two…');
    expect(fmt.truncate('short', 12)).toBe('short');
  });

  it('cuts mid-word rather than losing most of the text', () => {
    // A single long word has no boundary at all, so the guard sends it mid-word
    // rather than returning an ellipsis on its own.
    expect(fmt.truncate('Donaudampfschifffahrtsgesellschaft', 10)).toBe('Donaudampf…');
  });
});
