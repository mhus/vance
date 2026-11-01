import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProjectLiveState, RunnerPhase, type ProjectSummary, type SignalFrame } from '@vance/generated';

let handler: ((frame: SignalFrame) => void) | null = null;
const subscribeSignals = vi.fn(async () => {});
const unsubscribeSignals = vi.fn(async () => {});

vi.mock('@/ws/wsConnectionStore', () => ({
  onSignal: (_path: string, h: (frame: SignalFrame) => void) => {
    handler = h;
    return () => {
      handler = null;
    };
  },
  subscribeSignals: (path: string) => subscribeSignals(path),
  unsubscribeSignals: (path: string) => unsubscribeSignals(path),
}));

import { useProjectStatus } from './useProjectStatus';

function summary(name: string, extra: Partial<ProjectSummary> = {}): ProjectSummary {
  return { name, enabled: true, ...extra } as ProjectSummary;
}

describe('useProjectStatus', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-01T10:00:00Z'));
    subscribeSignals.mockClear();
    unsubscribeSignals.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('a project nobody reported on is unknown', () => {
    const status = useProjectStatus();

    expect(status.liveStateOf('never-seen')).toBe(ProjectLiveState.UNKNOWN);
    expect(status.runnerOf('never-seen')).toBeNull();
  });

  it('takes the start state from the project list', () => {
    const status = useProjectStatus();
    const later = Date.now() + 60_000;

    status.seed([
      summary('alpha', { liveState: ProjectLiveState.RUNNING, liveExpiresAtMs: later }),
      summary('beta', { liveState: ProjectLiveState.UNKNOWN }),
    ]);

    expect(status.liveStateOf('alpha')).toBe(ProjectLiveState.RUNNING);
    expect(status.liveStateOf('beta')).toBe(ProjectLiveState.UNKNOWN);
  });

  it('turns unknown by itself once the entry ran out', () => {
    const status = useProjectStatus();
    status.start();
    status.seed([
      summary('alpha', { liveState: ProjectLiveState.RUNNING, liveExpiresAtMs: Date.now() + 20_000 }),
    ]);
    expect(status.liveStateOf('alpha')).toBe(ProjectLiveState.RUNNING);

    vi.advanceTimersByTime(45_000);

    expect(status.liveStateOf('alpha')).toBe(ProjectLiveState.UNKNOWN);
    status.stop();
  });

  it('applies project frames from the status path', () => {
    const status = useProjectStatus();
    status.start();

    handler?.({
      path: '_status',
      signal: 'project-state',
      data: { project: 'alpha', liveState: 'STARTING', expiresAtMs: Date.now() + 60_000 },
    });

    expect(status.liveStateOf('alpha')).toBe(ProjectLiveState.STARTING);
    status.stop();
  });

  it('shows the runner of a project and follows its frames', () => {
    const status = useProjectStatus();
    status.start();
    status.seed([
      summary('alpha', {
        runner: { runnerId: 'runner-acme', phase: RunnerPhase.PENDING, expiresAtMs: Date.now() + 60_000 },
      }),
    ]);
    expect(status.runnerOf('alpha')?.phase).toBe(RunnerPhase.PENDING);

    handler?.({
      path: '_status',
      signal: 'runner-state',
      data: { runnerId: 'runner-acme', phase: 'READY', restarts: 2, expiresAtMs: Date.now() + 60_000 },
    });

    expect(status.runnerOf('alpha')).toEqual({ runnerId: 'runner-acme', phase: RunnerPhase.READY, restarts: 2 });
    status.stop();
  });

  it('a runner entry that ran out is shown as unknown but stays bound', () => {
    const status = useProjectStatus();
    status.start();
    status.seed([
      summary('alpha', {
        runner: { runnerId: 'runner-acme', phase: RunnerPhase.READY, expiresAtMs: Date.now() + 10_000 },
      }),
    ]);

    vi.advanceTimersByTime(30_000);

    expect(status.runnerOf('alpha')?.phase).toBe(RunnerPhase.UNKNOWN);
    status.stop();
  });

  it('subscribes once for any number of views and drops it with the last one', () => {
    const status = useProjectStatus();

    status.start();
    status.start();
    expect(subscribeSignals).toHaveBeenCalledTimes(1);

    status.stop();
    expect(unsubscribeSignals).not.toHaveBeenCalled();
    status.stop();
    expect(unsubscribeSignals).toHaveBeenCalledWith('_status');
  });
});
