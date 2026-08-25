import type { ProcessProgressNotification } from '@vance/generated';

/**
 * Reduces the {@code process-progress} side-channel into the state the
 * activity strip renders: what is running right now, and what the current
 * turn has done so far.
 *
 * <p>Pure and framework-free on purpose — the interesting part is the
 * correlation (open ping → close ping via {@code operationId}, turn
 * boundaries via the engine lifecycle) and that is worth testing without
 * mounting a component. The strip component owns only presentation and the
 * one-second tick that advances {@code now}.
 *
 * <p>Nothing here is persisted. The progress channel is explicitly
 * ephemeral (see {@code specification/user-progress-channel.md}), so a
 * reload legitimately starts from an empty strip.
 */

/**
 * Wire enums arrive as Java enum *names* (Jackson default) while the
 * generated TS enums are numeric — compare as strings at the boundary.
 * Same pattern as MessageBubble's RoleName and ProgressFeed's Kind.
 */
type KindName = 'METRICS' | 'PLAN' | 'STATUS' | 'REPLY';

/** Subset of {@code StatusTag} this strip reacts to. */
type TagName =
  | 'TOOL_START'
  | 'TOOL_END'
  | 'WAITING'
  | 'PROVIDER'
  | 'COMPACTION'
  | 'SCRIPT_PROGRESS'
  | 'DELEGATING'
  | 'SEARCH'
  | 'FETCH'
  | 'FILE_READ'
  | 'FILE_WRITE'
  | 'NODE_DONE'
  | 'PHASE_DONE'
  | 'INFO'
  | 'ENGINE_TURN_START'
  | 'ENGINE_TURN_END';

/** What an entry is about — drives the icon and the grouping in the list. */
export type ActivityOpKind =
  | 'tool'
  | 'provider'
  | 'compaction'
  | 'script'
  | 'delegate'
  | 'search'
  | 'fetch'
  | 'file'
  | 'milestone'
  | 'info';

export interface ActivityOp {
  /** Correlation id, or a synthetic one for pings that carry none. */
  id: string;
  kind: ActivityOpKind;
  /** Compact label: the bare tool name where we have one, else the ping text. */
  label: string;
  /** Longer prose — the ping's text for one-shots, the error cause for failures. */
  detail?: string;
  /**
   * Emitting process name, but only when it is *not* the chat process —
   * a worker's tool calls are the user's progress too, and hiding whose
   * they are would make the list read as one confused agent.
   */
  worker?: string;
  /** Client clock when the op appeared, for the live-ticking elapsed time. */
  startedAt: number;
  /** Client clock when it closed; unset while running. */
  endedAt?: number;
  /** Server-measured wall clock, authoritative once closed. */
  elapsedMs?: number;
  failed?: boolean;
}

/**
 * A {@code WAITING} ping: the process is parked — on a provider that has not
 * answered, on an image being generated, on a person answering a gate.
 *
 * <p>Held in its own slot rather than appended to {@link ActivityState.ops}
 * for two measured reasons. Fenchurch heartbeats the same wait every few
 * seconds with a *changing* text ("… 1:23 elapsed"), so a list would fill
 * with near-duplicates. And a wait is not an event that happened, it is a
 * condition that persists — the useful number is how long it has lasted,
 * which only survives if the ping refreshes a slot instead of creating a row.
 */
export interface WaitingNote {
  /** Text of the most recent ping. */
  label: string;
  /** Client clock of the *first* ping of this stretch — the timer's origin. */
  since: number;
  /** Emitting process, so only its own progress can clear the note. */
  processId: string;
  worker?: string;
}

export interface ActivityState {
  /** Ops of the current turn, oldest first — running and finished. */
  ops: ActivityOp[];
  /** Current parked condition, or {@code null} when nothing is waiting. */
  waiting: WaitingNote | null;
  /** Chat process is mid-turn (between its own TURN_START and TURN_END). */
  turnActive: boolean;
  /** Client clock of the current turn's start, for the summary duration. */
  turnStartedAt: number | null;
  /** Client clock of the turn's end, once it ended. */
  turnEndedAt: number | null;
  /** Monotonic counter for synthesising ids of uncorrelated pings. */
  seq: number;
}

/**
 * Hard ceiling on retained ops. A long Frankie turn can call hundreds of
 * tools; the strip only ever shows the tail, and an unbounded array in a
 * tab left open for a day is a leak.
 */
export const OPS_CAP = 200;

export function createActivityState(): ActivityState {
  return {
    ops: [],
    waiting: null,
    turnActive: false,
    turnStartedAt: null,
    turnEndedAt: null,
    seq: 0,
  };
}

function kindOf(event: ProcessProgressNotification): KindName {
  return event.kind as unknown as KindName;
}

function tagOf(event: ProcessProgressNotification): TagName | null {
  const tag = event.status?.tag as unknown as string | undefined;
  return (tag ?? null) as TagName | null;
}

/**
 * Tags that describe something already finished. All of them arrive without
 * an {@code operationId}: the spec's lifecycle table pairs {@code DELEGATING}
 * and {@code PHASE_DONE} with an opening tag, but every emitter in the tree
 * (Marvin, Magrathea, Slartibartfast) sends them through plain
 * {@code emitStatus}, so treating them as correlated opens would leave rows
 * spinning forever.
 *
 * <p>{@code SEARCH}, {@code FILE_READ} and {@code FILE_WRITE} have no emitter
 * at all today — mapped because they are part of the wire enum, not because
 * they are exercised.
 *
 * <p>{@code INFO} only ever arrives at {@code progress=verbose}; the NORMAL
 * level filters it server-side.
 */
const ONE_SHOT_KINDS: Partial<Record<TagName, ActivityOpKind>> = {
  PROVIDER: 'provider',
  COMPACTION: 'compaction',
  SCRIPT_PROGRESS: 'script',
  DELEGATING: 'delegate',
  SEARCH: 'search',
  FETCH: 'fetch',
  FILE_READ: 'file',
  FILE_WRITE: 'file',
  NODE_DONE: 'milestone',
  PHASE_DONE: 'milestone',
  INFO: 'info',
};

/**
 * Drops the parked note when the process that set it shows forward progress.
 * Scoped to the emitter on purpose: a worker's tool call says nothing about
 * whether the chat process is still parked on a gate.
 */
function clearWaitingFrom(state: ActivityState, processId: string): void {
  if (state.waiting !== null && state.waiting.processId === processId) {
    state.waiting = null;
  }
}

/**
 * Folds one progress frame into {@code state}, mutating in place.
 *
 * @param chatProcessName name of the session's own chat process. Turn
 *        boundaries are honoured only from that process: a worker ending
 *        its turn must not collapse the strip while the chat is still
 *        working.
 * @param now client clock, injected so tests don't need a fake timer.
 * @returns whether anything changed — lets the caller skip a re-render.
 */
export function applyProgress(
  state: ActivityState,
  event: ProcessProgressNotification,
  chatProcessName: string | null,
  now: number,
): boolean {
  if (kindOf(event) !== 'STATUS' || !event.status) return false;
  const tag = tagOf(event);
  if (!tag) return false;

  const fromChatProcess = chatProcessName !== null && event.processName === chatProcessName;

  if (tag === 'ENGINE_TURN_START') {
    if (!fromChatProcess) return false;
    // A new turn supersedes the previous one's list — the summary of the
    // turn that just ended has served its purpose the moment the next
    // one starts.
    state.ops = [];
    state.waiting = null;
    state.turnActive = true;
    state.turnStartedAt = now;
    state.turnEndedAt = null;
    return true;
  }

  if (tag === 'ENGINE_TURN_END') {
    if (!fromChatProcess) return false;
    state.turnActive = false;
    state.turnEndedAt = now;
    // The parked note deliberately survives the turn end. Parking on a gate
    // *is* how a turn ends (Vogon goes BLOCKED and yields), so clearing here
    // would erase the one line that explains why nothing else follows. A wait
    // that merely sat inside the turn is cleared by its own tool's close.
    return true;
  }

  const worker = fromChatProcess ? undefined : event.processName;

  if (tag === 'WAITING') {
    const prev = state.waiting;
    const sameStretch = prev !== null && prev.processId === event.processId;
    state.waiting = {
      label: event.status.text,
      // Heartbeats re-send the same wait with a fresh text. Keeping the
      // original `since` is the whole point — the number worth showing is how
      // long the wait has lasted, not when the last ping landed.
      since: sameStretch ? prev.since : now,
      processId: event.processId,
      worker,
    };
    return true;
  }

  if (tag === 'TOOL_START') {
    clearWaitingFrom(state, event.processId);
    const op: ActivityOp = {
      id: event.status.operationId ?? `op-${++state.seq}`,
      kind: 'tool',
      label: event.status.tool ?? event.status.text,
      worker,
      startedAt: now,
    };
    push(state, op);
    return true;
  }

  if (tag === 'TOOL_END') {
    clearWaitingFrom(state, event.processId);
    const opId = event.status.operationId;
    const open = opId
      ? state.ops.find((o) => o.id === opId && o.endedAt === undefined)
      : undefined;
    // No matching open op — a mismatched or post-reload close ping. Record
    // it as an already-finished entry rather than dropping it; the user
    // still learns that a tool ran.
    const op = open ?? {
      id: opId ?? `op-${++state.seq}`,
      kind: 'tool' as const,
      label: event.status.tool ?? event.status.text,
      worker,
      startedAt: now,
    };
    op.endedAt = now;
    op.elapsedMs = event.status.usage?.elapsedMs ?? undefined;
    op.failed = event.status.failed === true;
    if (op.failed && event.status.detail) op.detail = event.status.detail;
    if (!open) push(state, op);
    return true;
  }

  const oneShot = ONE_SHOT_KINDS[tag];
  if (oneShot) {
    clearWaitingFrom(state, event.processId);
    push(state, {
      id: event.status.operationId ?? `op-${++state.seq}`,
      kind: oneShot,
      label: event.status.text,
      detail: event.status.detail ?? undefined,
      worker,
      startedAt: now,
      // One-shot pings describe something that already happened — they
      // are never "running", so the strip must not tick a timer on them.
      endedAt: now,
    });
    return true;
  }

  return false;
}

function push(state: ActivityState, op: ActivityOp): void {
  state.ops.push(op);
  if (state.ops.length > OPS_CAP) {
    state.ops.splice(0, state.ops.length - OPS_CAP);
  }
}

/** What the strip needs to render, derived from the state and the clock. */
export interface ActivityView {
  visible: boolean;
  /** The op to headline: the newest still-running one. */
  current: ActivityOp | null;
  /**
   * Parked condition, if any. Coexists with {@link current}: an image
   * generation is a running {@code image_generate} tool *and* a wait on the
   * provider, and the strip shows both — the tool names what is happening,
   * the wait explains why it is taking this long.
   */
  waiting: WaitingNote | null;
  /** Number of tool calls in this turn (one-shots are not tools). */
  toolCount: number;
  /**
   * The duration to headline: the running op's, else the wait's, else the
   * whole turn's. Precedence follows the headline, so the number always
   * belongs to the text next to it.
   */
  elapsedMs: number;
  /** Nothing pending — render the static summary instead of a spinner. */
  done: boolean;
  failedCount: number;
}

export function activityView(state: ActivityState, now: number): ActivityView {
  let current: ActivityOp | null = null;
  for (let i = state.ops.length - 1; i >= 0; i -= 1) {
    if (state.ops[i].endedAt === undefined) {
      current = state.ops[i];
      break;
    }
  }
  const waiting = state.waiting;
  const toolCount = state.ops.reduce((n, o) => (o.kind === 'tool' ? n + 1 : n), 0);
  const failedCount = state.ops.reduce((n, o) => (o.failed ? n + 1 : n), 0);
  // Something running or parked always shows, even with no active chat turn —
  // that is the background-worker and the gate case, and both are exactly the
  // silence the user wants explained.
  const visible = state.turnActive
    || current !== null
    || waiting !== null
    || state.ops.length > 0;
  const done = !state.turnActive && current === null && waiting === null;
  let elapsedMs: number;
  if (current) {
    elapsedMs = now - current.startedAt;
  } else if (waiting) {
    elapsedMs = now - waiting.since;
  } else {
    elapsedMs = turnDuration(state, now);
  }
  return { visible, current, waiting, toolCount, elapsedMs, done, failedCount };
}

function turnDuration(state: ActivityState, now: number): number {
  if (state.turnStartedAt === null) return 0;
  return (state.turnEndedAt ?? now) - state.turnStartedAt;
}

/** Ops newest-first — the order the expanded list reads in. */
export function opsNewestFirst(state: ActivityState): ActivityOp[] {
  return state.ops.slice().reverse();
}

/**
 * Duration of a single op: the server's measurement once we have it (it
 * excludes our own frame latency), the client delta while running.
 */
export function opElapsedMs(op: ActivityOp, now: number): number {
  if (op.elapsedMs !== undefined) return op.elapsedMs;
  return (op.endedAt ?? now) - op.startedAt;
}

/**
 * Human duration for the strip. Sub-second stays in milliseconds (a
 * finished tool that took 40ms should not read "0s"), the first minute
 * gets one decimal only while it is still short enough for the digit to
 * mean something, and beyond a minute it becomes m:ss.
 *
 * @param locale explicit for tests; production passes the UI locale so the
 *        decimal separator follows it.
 */
export function formatDuration(ms: number, locale?: string): string {
  const safe = Math.max(0, Math.round(ms));
  if (safe < 1_000) return `${safe}ms`;
  if (safe < 60_000) {
    const digits = safe < 10_000 ? 1 : 0;
    const seconds = new Intl.NumberFormat(locale, {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(safe / 1_000);
    return `${seconds}s`;
  }
  const totalSeconds = Math.floor(safe / 1_000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}
