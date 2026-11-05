import { describe, expect, it } from 'vitest';
import { defaultOutcome, parseSimulatedOutput } from './simulate';

describe('parseSimulatedOutput', () => {
  it('has no output for empty or blank text', () => {
    expect(parseSimulatedOutput('')).toBeUndefined();
    expect(parseSimulatedOutput('   \n ')).toBeUndefined();
  });

  it('reads JSON as structure, so later states can use its fields', () => {
    expect(parseSimulatedOutput('{"ticket": "ABC-1", "n": 2}')).toEqual({ ticket: 'ABC-1', n: 2 });
    expect(parseSimulatedOutput('[1, 2]')).toEqual([1, 2]);
    expect(parseSimulatedOutput('42')).toBe(42);
  });

  it('keeps anything that is not JSON as the text it was', () => {
    expect(parseSimulatedOutput('The draft is fine.')).toBe('The draft is fine.');
    expect(parseSimulatedOutput('{not json')).toBe('{not json');
  });
});

describe('defaultOutcome', () => {
  it('prefers success', () => {
    expect(defaultOutcome(['partial', 'success', 'technical_error'])).toBe('success');
  });

  it('takes the first outcome when there is no success', () => {
    expect(defaultOutcome(['approved', 'rejected'])).toBe('approved');
  });

  it('is empty when there is nothing to choose', () => {
    expect(defaultOutcome([])).toBe('');
  });
});
