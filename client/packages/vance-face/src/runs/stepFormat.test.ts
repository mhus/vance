import { describe, expect, it } from 'vitest';
import { formatDuration, formatStepTime } from './stepFormat';

describe('formatDuration', () => {
  it('shows milliseconds below a second', () => {
    expect(formatDuration(0)).toBe('0 ms');
    expect(formatDuration(850)).toBe('850 ms');
  });

  it('shows seconds with one decimal below a minute', () => {
    expect(formatDuration(1234)).toBe('1.2 s');
    expect(formatDuration(59_000)).toBe('59.0 s');
  });

  it('shows minutes and seconds from a minute on', () => {
    expect(formatDuration(60_000)).toBe('1 min');
    expect(formatDuration(185_000)).toBe('3 min 5 s');
  });

  it('never shows a negative duration', () => {
    expect(formatDuration(-5)).toBe('0 ms');
  });
});

describe('formatStepTime', () => {
  it('formats an ISO string as a time of day', () => {
    expect(formatStepTime('2026-11-04T10:15:30Z', 'en', 'UTC')).toBe('10:15:30 AM');
  });

  it('accepts a Date as well', () => {
    expect(formatStepTime(new Date('2026-11-04T10:15:30Z'), 'en', 'UTC')).toBe('10:15:30 AM');
  });

  it('returns nothing for a value that is not a date', () => {
    expect(formatStepTime('not a date', 'en')).toBe('');
  });
});
