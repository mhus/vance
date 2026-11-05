import { describe, expect, it } from 'vitest';
import { RunStatus } from '@vance/generated';
import {
  PAUSED_POLL_INTERVAL_MS, pollPlan, RUN_FIRST_POLL_MS, RUN_POLL_INTERVAL_MS,
} from './polling';

describe('pollPlan', () => {
  it('reads a working or stopping run quickly, the first time sooner still', () => {
    for (const s of [RunStatus.RUNNING, RunStatus.STOPPING]) {
      expect(pollPlan(s)).toEqual({ firstMs: RUN_FIRST_POLL_MS, everyMs: RUN_POLL_INTERVAL_MS });
    }
    expect(RUN_FIRST_POLL_MS).toBeLessThan(RUN_POLL_INTERVAL_MS);
  });

  it('reads a paused run too — in-flight work and timers still change it — but slower', () => {
    expect(pollPlan(RunStatus.PAUSED)).toEqual({ firstMs: null, everyMs: PAUSED_POLL_INTERVAL_MS });
    expect(PAUSED_POLL_INTERVAL_MS).toBeGreaterThan(RUN_POLL_INTERVAL_MS);
  });

  it('does not read a run that waits for somebody or has ended', () => {
    for (const s of [RunStatus.WAITING, RunStatus.DONE, RunStatus.FAILED, RunStatus.STOPPED]) {
      expect(pollPlan(s)).toBeNull();
    }
  });

  it('does not read when there is no run', () => {
    expect(pollPlan(undefined)).toBeNull();
  });
});
