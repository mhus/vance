import { describe, expect, it } from 'vitest';
import { RunStatus, type RunStepDto } from '@vance/generated';
import { buildOverlay } from './stepOverlay';

const step = (name: string, outcome?: string, durationMs?: number): RunStepDto =>
  ({ name, outcome, durationMs, outputTruncated: false });

describe('buildOverlay', () => {
  it('marks finished states ok and the standing one current', () => {
    const overlay = buildOverlay([step('a', 'success', 120), step('b')], RunStatus.RUNNING);
    expect(overlay.a).toEqual({ tone: 'ok', entries: 1, outcome: 'success', durationMs: 120 });
    expect(overlay.b).toEqual({ tone: 'current', entries: 1 });
  });

  it('shows the standing state of a paused run as halted', () => {
    const overlay = buildOverlay([step('a', 'success'), step('b')], RunStatus.PAUSED);
    expect(overlay.b.tone).toBe('halted');
  });

  it('colours error outcomes apart from successes', () => {
    const overlay = buildOverlay([
      step('a', 'technical_error'), step('b', 'timeout'), step('c', 'approved'),
    ], RunStatus.DONE);
    expect(overlay.a.tone).toBe('other');
    expect(overlay.b.tone).toBe('other');
    expect(overlay.c.tone).toBe('ok');
  });

  it('counts a state entered more than once and keeps its latest outcome', () => {
    const overlay = buildOverlay([
      step('write', 'revise'), step('check', 'success'), step('write', 'success', 50),
    ], RunStatus.DONE);
    expect(overlay.write).toEqual({ tone: 'ok', entries: 2, outcome: 'success', durationMs: 50 });
  });

  it('does not call the last step current when the run is over', () => {
    const overlay = buildOverlay([step('a', 'success'), step('b')], RunStatus.STOPPED);
    expect(overlay.b.tone).toBe('other');
  });

  it('is empty for a run without steps', () => {
    expect(buildOverlay(undefined, RunStatus.RUNNING)).toEqual({});
    expect(buildOverlay([], RunStatus.RUNNING)).toEqual({});
  });
});
