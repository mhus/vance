import { describe, expect, it } from 'vitest';
import { formatDuration } from './formatDuration';

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
