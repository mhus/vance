import { describe, expect, it } from 'vitest';
import { formatStepTime } from './stepFormat';

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
