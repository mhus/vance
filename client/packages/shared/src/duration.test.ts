import { describe, expect, it } from 'vitest';
import { bestUnit, formatDuration, formatDurationAmount, parseDuration } from './duration';

describe('parseDuration', () => {
  it('reads units, decimal comma and point', () => {
    expect(parseDuration('1,5h')).toEqual({ hours: 1.5, typedUnit: 'h' });
    expect(parseDuration('1.5 H')).toEqual({ hours: 1.5, typedUnit: 'h' });
    expect(parseDuration('2d')).toEqual({ hours: 16, typedUnit: 'd' });
    expect(parseDuration('1w')).toEqual({ hours: 40, typedUnit: 'w' });
    expect(parseDuration('30min')).toEqual({ hours: 0.5, typedUnit: 'min' });
    expect(parseDuration('90m')?.hours).toBeCloseTo(1.5);
  });

  it('reads a bare number in the default unit', () => {
    expect(parseDuration('3')).toEqual({ hours: 3, typedUnit: null });
    expect(parseDuration('2', 'd')).toEqual({ hours: 16, typedUnit: null });
    expect(parseDuration('45', 'min')?.hours).toBeCloseTo(0.75);
  });

  it('adds several segments', () => {
    expect(parseDuration('1d 4h')).toEqual({ hours: 12, typedUnit: 'h' });
    expect(parseDuration('1h30min')?.hours).toBeCloseTo(1.5);
  });

  it('rejects everything else', () => {
    for (const bad of ['', '  ', 'abc', '1x', '1,', '1 2h', '-1h', '1h h', '1..5']) {
      expect(parseDuration(bad), bad).toBeNull();
    }
  });
});

describe('bestUnit / formatDurationAmount', () => {
  it('picks the unit a value reads best in', () => {
    expect(bestUnit(0.5)).toBe('min');
    expect(bestUnit(1.5)).toBe('h');
    expect(bestUnit(8)).toBe('d');
    expect(bestUnit(16)).toBe('d');
    expect(bestUnit(12)).toBe('h');
  });

  it('formats without trailing zeros and rounds to two decimals', () => {
    expect(formatDurationAmount(1.5, 'h')).toBe('1.5');
    expect(formatDurationAmount(16, 'd')).toBe('2');
    expect(formatDurationAmount(0.5, 'min')).toBe('30');
    expect(formatDurationAmount(1 / 3, 'h')).toBe('0.33');
  });
});

describe('formatDuration', () => {
  it('writes a value in its best unit', () => {
    expect(formatDuration(1.5)).toBe('1.5h');
    expect(formatDuration(16)).toBe('2d');
    expect(formatDuration(0.5)).toBe('30min');
    expect(formatDuration(5)).toBe('5h');
  });
});
