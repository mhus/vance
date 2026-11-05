import { describe, expect, it } from 'vitest';
import { RunStatus } from '@vance/generated';
import { canChangeBreakpoints, toggleBreakpoint } from './breakpoints';

describe('toggleBreakpoint', () => {
  it('adds a state at the end', () => {
    expect(toggleBreakpoint(['a'], 'b')).toEqual(['a', 'b']);
  });

  it('removes a state that is already a breakpoint, keeping the order of the rest', () => {
    expect(toggleBreakpoint(['a', 'b', 'c'], 'b')).toEqual(['a', 'c']);
  });

  it('works on an empty set and does not change its input', () => {
    const input = ['a'];
    expect(toggleBreakpoint([], 'a')).toEqual(['a']);
    toggleBreakpoint(input, 'b');
    expect(input).toEqual(['a']);
  });
});

describe('canChangeBreakpoints', () => {
  it('is true while the run has states ahead of it', () => {
    for (const s of [RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.WAITING, RunStatus.STOPPING]) {
      expect(canChangeBreakpoints(s)).toBe(true);
    }
  });

  it('is false once the run has ended, or when there is no run', () => {
    for (const s of [RunStatus.DONE, RunStatus.FAILED, RunStatus.STOPPED]) {
      expect(canChangeBreakpoints(s)).toBe(false);
    }
    expect(canChangeBreakpoints(undefined)).toBe(false);
  });
});
