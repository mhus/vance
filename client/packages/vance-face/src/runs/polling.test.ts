import { describe, expect, it } from 'vitest';
import { RunStatus } from '@vance/generated';
import { isMoving } from './polling';

describe('isMoving', () => {
  it('is true while the run works or is being stopped', () => {
    expect(isMoving(RunStatus.RUNNING)).toBe(true);
    expect(isMoving(RunStatus.STOPPING)).toBe(true);
  });

  it('is false for a run that waits, is halted or has ended', () => {
    for (const s of [RunStatus.PAUSED, RunStatus.WAITING, RunStatus.DONE, RunStatus.FAILED, RunStatus.STOPPED]) {
      expect(isMoving(s)).toBe(false);
    }
  });

  it('is false when there is no run', () => {
    expect(isMoving(undefined)).toBe(false);
  });
});

describe('poll timing', () => {
  it('reads the first time sooner than the regular beat', async () => {
    const { RUN_FIRST_POLL_MS, RUN_POLL_INTERVAL_MS } = await import('./polling');
    expect(RUN_FIRST_POLL_MS).toBeLessThan(RUN_POLL_INTERVAL_MS);
  });
});
