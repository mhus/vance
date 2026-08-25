import { describe, expect, it } from 'vitest';
import type { ProcessProgressNotification } from '@vance/generated';
import {
  activityView,
  applyProgress,
  createActivityState,
  formatDuration,
  opElapsedMs,
  opsNewestFirst,
  OPS_CAP,
  type ActivityState,
} from './chatActivity';

const CHAT = 'chat';

/**
 * Builds a STATUS frame the way the wire delivers it — enum *names*, not
 * the numeric TS enum members. The cast mirrors what the production code
 * has to cope with.
 */
function status(
  tag: string,
  extra: {
    tool?: string;
    text?: string;
    operationId?: string;
    detail?: string;
    failed?: boolean;
    elapsedMs?: number;
    processName?: string;
    processId?: string;
  } = {},
): ProcessProgressNotification {
  return {
    // Default the id alongside the name so a "worker" frame is a different
    // process in both dimensions, the way the wire delivers it.
    processId: extra.processId ?? (extra.processName ? `id-${extra.processName}` : 'p1'),
    processName: extra.processName ?? CHAT,
    engine: 'arthur',
    sessionId: 's1',
    kind: 'STATUS' as unknown as ProcessProgressNotification['kind'],
    status: {
      tag: tag as unknown as NonNullable<ProcessProgressNotification['status']>['tag'],
      text: extra.text ?? `${tag} ping`,
      tool: extra.tool,
      operationId: extra.operationId,
      detail: extra.detail,
      failed: extra.failed,
      usage: extra.elapsedMs === undefined
        ? undefined
        : ({ elapsedMs: extra.elapsedMs } as unknown as NonNullable<
          NonNullable<ProcessProgressNotification['status']>['usage']
        >),
    },
  } as ProcessProgressNotification;
}

function feed(
  state: ActivityState,
  events: Array<[ProcessProgressNotification, number]>,
): void {
  for (const [event, now] of events) {
    applyProgress(state, event, CHAT, now);
  }
}

describe('applyProgress', () => {
  it('pairs a tool start with its close via operationId', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'doc_read', operationId: 'op-a' }), 1_000],
      [status('TOOL_END', { tool: 'doc_read', operationId: 'op-a', elapsedMs: 412, failed: false }), 1_500],
    ]);

    expect(state.ops).toHaveLength(1);
    expect(state.ops[0]).toMatchObject({
      label: 'doc_read',
      kind: 'tool',
      endedAt: 1_500,
      elapsedMs: 412,
      failed: false,
    });
  });

  it('keeps concurrent tool calls apart', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'doc_read', operationId: 'op-a' }), 1_000],
      [status('TOOL_START', { tool: 'web_fetch', operationId: 'op-b' }), 1_100],
      [status('TOOL_END', { tool: 'doc_read', operationId: 'op-a', elapsedMs: 50 }), 1_200],
    ]);

    const view = activityView(state, 1_300);
    // op-a closed, so the headline is the one still running.
    expect(view.current?.label).toBe('web_fetch');
    expect(view.toolCount).toBe(2);
  });

  it('marks a failed tool and carries its cause', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'exec_run', operationId: 'op-a' }), 1_000],
      [status('TOOL_END', {
        tool: 'exec_run',
        operationId: 'op-a',
        failed: true,
        detail: 'exit code 1',
      }), 1_900],
    ]);

    expect(state.ops[0].failed).toBe(true);
    expect(state.ops[0].detail).toBe('exit code 1');
    expect(activityView(state, 2_000).failedCount).toBe(1);
  });

  it('records a close ping that has no matching open op', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_END', { tool: 'doc_write', operationId: 'orphan', elapsedMs: 7 }), 500],
    ]);

    expect(state.ops).toHaveLength(1);
    expect(state.ops[0]).toMatchObject({ label: 'doc_write', endedAt: 500 });
    expect(activityView(state, 600).current).toBeNull();
  });

  it('treats provider, compaction and script pings as finished one-shots', () => {
    const state = createActivityState();
    feed(state, [
      [status('PROVIDER', { text: 'Retrying after timeout' }), 1_000],
      [status('COMPACTION', { text: 'SOFT · 8 msgs → 1240 chars' }), 1_100],
      [status('SCRIPT_PROGRESS', { text: '300/1000 rows' }), 1_200],
    ]);

    expect(state.ops.map((o) => o.kind)).toEqual(['provider', 'compaction', 'script']);
    // One-shots describe the past — nothing may tick a running timer.
    expect(state.ops.every((o) => o.endedAt !== undefined)).toBe(true);
    expect(activityView(state, 5_000).current).toBeNull();
    expect(activityView(state, 5_000).toolCount).toBe(0);
  });

  it('records delegation, fetch and milestone pings as one-shots too', () => {
    const state = createActivityState();
    feed(state, [
      [status('DELEGATING', { text: 'CALL_RECIPE → analyze' }), 1_000],
      [status('FETCH', { text: 'Fetching https://example.com/a' }), 1_100],
      [status('NODE_DONE', { text: "Node 'audit' done — 3 children" }), 1_200],
      [status('PHASE_DONE', { text: 'Validating done' }), 1_300],
      [status('SEARCH', { text: "Searching for: 'x'" }), 1_400],
      [status('FILE_WRITE', { text: 'Writing /workspace/notes.md' }), 1_500],
      [status('INFO', { text: 'aside' }), 1_600],
    ]);

    // Every emitter in the tree sends these through plain emitStatus without
    // an operationId — modelled as open ops they would spin forever.
    expect(state.ops.map((o) => o.kind)).toEqual([
      'delegate', 'fetch', 'milestone', 'milestone', 'search', 'file', 'info',
    ]);
    expect(state.ops.every((o) => o.endedAt !== undefined)).toBe(true);
    // None of them is a tool call, so none inflates the tool count.
    expect(activityView(state, 2_000).toolCount).toBe(0);
  });

  it('ignores non-status kinds', () => {
    const state = createActivityState();
    const metrics = {
      processId: 'p1',
      processName: CHAT,
      engine: 'arthur',
      sessionId: 's1',
      kind: 'METRICS' as unknown as ProcessProgressNotification['kind'],
    } as ProcessProgressNotification;

    expect(applyProgress(state, metrics, CHAT, 1_000)).toBe(false);
    expect(state.ops).toHaveLength(0);
  });

  it('caps the retained op list', () => {
    const state = createActivityState();
    for (let i = 0; i < OPS_CAP + 25; i += 1) {
      applyProgress(state, status('TOOL_START', { tool: `t${i}`, operationId: `op-${i}` }), CHAT, i);
    }

    expect(state.ops).toHaveLength(OPS_CAP);
    // The tail is what survives.
    expect(state.ops[state.ops.length - 1].label).toBe(`t${OPS_CAP + 24}`);
  });
});

describe('turn boundaries', () => {
  it('clears the previous turn on a new turn start', () => {
    const state = createActivityState();
    feed(state, [
      [status('ENGINE_TURN_START'), 1_000],
      [status('TOOL_START', { tool: 'doc_read', operationId: 'op-a' }), 1_100],
      [status('TOOL_END', { tool: 'doc_read', operationId: 'op-a', elapsedMs: 10 }), 1_200],
      [status('ENGINE_TURN_END'), 1_300],
    ]);
    expect(activityView(state, 1_400)).toMatchObject({ done: true, toolCount: 1, elapsedMs: 300 });

    applyProgress(state, status('ENGINE_TURN_START'), CHAT, 2_000);
    expect(state.ops).toHaveLength(0);
    expect(activityView(state, 2_000)).toMatchObject({ done: false, visible: true, toolCount: 0 });
  });

  it("does not let a worker's turn end collapse the chat strip", () => {
    const state = createActivityState();
    feed(state, [
      [status('ENGINE_TURN_START'), 1_000],
      [status('ENGINE_TURN_END', { processName: 'worker-7' }), 1_100],
    ]);

    expect(state.turnActive).toBe(true);
    expect(activityView(state, 1_200).done).toBe(false);
  });

  it("attributes a worker's tool call to the worker", () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'file_write', operationId: 'op-w', processName: 'worker-7' }), 1_000],
    ]);

    expect(state.ops[0].worker).toBe('worker-7');
    // Running work with no active chat turn is still worth showing — that
    // is the background-worker silence the strip exists to explain.
    expect(activityView(state, 1_500)).toMatchObject({ visible: true, done: false });
  });

  it('stays hidden while nothing has happened', () => {
    expect(activityView(createActivityState(), 1_000).visible).toBe(false);
  });
});

describe('waiting', () => {
  it('keeps one note across heartbeats whose text changes', () => {
    const state = createActivityState();
    feed(state, [
      [status('WAITING', { text: 'Generating image (nano-banana) …' }), 1_000],
      [status('WAITING', { text: 'Generating image (nano-banana) … 0:05 elapsed' }), 6_000],
      [status('WAITING', { text: 'Generating image (nano-banana) … 0:10 elapsed' }), 11_000],
    ]);

    // One slot, not three rows — Fenchurch reworded the same wait twice.
    expect(state.ops).toHaveLength(0);
    expect(state.waiting?.label).toContain('0:10 elapsed');
    // …and the clock still runs from the FIRST ping, which is the number the
    // user actually wants.
    expect(state.waiting?.since).toBe(1_000);
    expect(activityView(state, 12_000).elapsedMs).toBe(11_000);
  });

  it('is the headline when nothing is running, and blocks "done"', () => {
    const state = createActivityState();
    feed(state, [
      [status('ENGINE_TURN_START'), 1_000],
      [status('WAITING', { text: 'Waiting for approval on inbox item #42' }), 1_100],
      [status('ENGINE_TURN_END'), 1_200],
    ]);

    const view = activityView(state, 5_000);
    // Parking on a gate IS how the turn ends — clearing the note here would
    // erase the only line explaining why nothing follows.
    expect(view.waiting?.label).toBe('Waiting for approval on inbox item #42');
    expect(view.done).toBe(false);
    expect(view.visible).toBe(true);
    expect(view.current).toBeNull();
    expect(view.elapsedMs).toBe(3_900);
  });

  it('coexists with the running tool it explains', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'image_generate', operationId: 'op-a' }), 1_000],
      [status('WAITING', { text: 'Generating image (nano-banana) …' }), 1_200],
    ]);

    const view = activityView(state, 5_000);
    expect(view.current?.label).toBe('image_generate');
    expect(view.waiting).not.toBeNull();
    // The headline duration belongs to the headline text — the op, not the wait.
    expect(view.elapsedMs).toBe(4_000);
  });

  it('is cleared by the close of the tool it was waiting inside', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'image_generate', operationId: 'op-a' }), 1_000],
      [status('WAITING', { text: 'Generating image …' }), 1_200],
      [status('TOOL_END', { tool: 'image_generate', operationId: 'op-a', elapsedMs: 9_000 }), 10_000],
    ]);

    expect(state.waiting).toBeNull();
    expect(activityView(state, 11_000).done).toBe(true);
  });

  it("survives a different process's progress", () => {
    const state = createActivityState();
    feed(state, [
      [status('WAITING', { text: 'Waiting for approval on inbox item #42' }), 1_000],
      // A worker doing its own work says nothing about whether the chat
      // process is still parked on its gate.
      [status('TOOL_START', { tool: 'file_write', operationId: 'op-w', processName: 'worker-7' }), 1_100],
      [status('TOOL_END', { tool: 'file_write', operationId: 'op-w', processName: 'worker-7' }), 1_200],
    ]);

    expect(state.waiting?.label).toBe('Waiting for approval on inbox item #42');
  });

  it('restarts the clock when a different process parks', () => {
    const state = createActivityState();
    feed(state, [
      [status('WAITING', { text: 'chat parked' }), 1_000],
      [status('WAITING', { text: 'worker parked', processName: 'worker-7' }), 5_000],
    ]);

    expect(state.waiting?.since).toBe(5_000);
    expect(state.waiting?.worker).toBe('worker-7');
  });

  it('is dropped by a new turn', () => {
    const state = createActivityState();
    feed(state, [
      [status('WAITING', { text: 'Waiting for approval' }), 1_000],
      [status('ENGINE_TURN_START'), 2_000],
    ]);

    expect(state.waiting).toBeNull();
  });
});

describe('elapsed time', () => {
  it('ticks from the client clock while running and freezes on the server value', () => {
    const state = createActivityState();
    applyProgress(state, status('TOOL_START', { tool: 'slow', operationId: 'op-a' }), CHAT, 1_000);
    expect(opElapsedMs(state.ops[0], 4_000)).toBe(3_000);

    applyProgress(
      state,
      status('TOOL_END', { tool: 'slow', operationId: 'op-a', elapsedMs: 2_800 }),
      CHAT,
      4_000,
    );
    expect(opElapsedMs(state.ops[0], 9_999)).toBe(2_800);
  });

  it('falls back to the client delta when the server sent no usage', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'x', operationId: 'op-a' }), 1_000],
      [status('TOOL_END', { tool: 'x', operationId: 'op-a' }), 1_750],
    ]);

    expect(opElapsedMs(state.ops[0], 5_000)).toBe(750);
  });
});

describe('formatDuration', () => {
  it('keeps sub-second work in milliseconds so it never reads as zero', () => {
    expect(formatDuration(40, 'en-US')).toBe('40ms');
    expect(formatDuration(999, 'en-US')).toBe('999ms');
  });

  it('shows a decimal only while it still carries information', () => {
    expect(formatDuration(4_240, 'en-US')).toBe('4.2s');
    expect(formatDuration(12_600, 'en-US')).toBe('13s');
  });

  it('follows the locale decimal separator', () => {
    expect(formatDuration(4_240, 'de-DE')).toBe('4,2s');
  });

  it('switches to m:ss past a minute', () => {
    expect(formatDuration(83_000, 'en-US')).toBe('1:23');
    expect(formatDuration(3_605_000, 'en-US')).toBe('60:05');
  });

  it('clamps a negative clock skew to zero', () => {
    expect(formatDuration(-5, 'en-US')).toBe('0ms');
  });
});

describe('opsNewestFirst', () => {
  it('reverses without mutating the state', () => {
    const state = createActivityState();
    feed(state, [
      [status('TOOL_START', { tool: 'a', operationId: 'op-a' }), 1],
      [status('TOOL_START', { tool: 'b', operationId: 'op-b' }), 2],
    ]);

    expect(opsNewestFirst(state).map((o) => o.label)).toEqual(['b', 'a']);
    expect(state.ops.map((o) => o.label)).toEqual(['a', 'b']);
  });
});
