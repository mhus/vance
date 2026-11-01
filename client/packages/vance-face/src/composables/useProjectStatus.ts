import { computed, reactive, ref, type ComputedRef } from 'vue';
import {
  ProjectLiveState,
  RunnerPhase,
  type ProjectSummary,
  type RunnerStatusDto,
  type SignalFrame,
} from '@vance/generated';
import { onSignal, subscribeSignals, unsubscribeSignals } from '@/ws/wsConnectionStore';

/**
 * Live state of projects and their runners, for display only.
 *
 * <p>The start state comes with the project list ({@link seed}); changes arrive on the
 * {@code _status} path of the signals channel. An entry whose {@code expiresAtMs} has passed
 * reads as unknown, so a writer that went silent never leaves an old value on screen.
 */

const STATUS_PATH = '_status';
/** How often expiry is re-evaluated; entries live for minutes, so this can be coarse. */
const TICK_MS = 15_000;

interface LiveEntry {
  state: ProjectLiveState;
  expiresAtMs: number | null;
}

const live = reactive<Record<string, LiveEntry>>({});
const runnerIdOfProject = reactive<Record<string, string>>({});
const runners = reactive<Record<string, RunnerStatusDto>>({});
const nowMs = ref(Date.now());

let users = 0;
let off: (() => void) | null = null;
let timer: ReturnType<typeof setInterval> | null = null;

function expired(expiresAtMs: number | null | undefined): boolean {
  return expiresAtMs != null && expiresAtMs <= nowMs.value;
}

function onFrame(frame: SignalFrame): void {
  nowMs.value = Date.now();
  const data = frame.data as Record<string, unknown> | undefined;
  if (!data) return;
  if (frame.signal === 'project-state' && typeof data.project === 'string') {
    live[data.project] = {
      state: data.liveState as ProjectLiveState,
      expiresAtMs: typeof data.expiresAtMs === 'number' ? data.expiresAtMs : null,
    };
  } else if (frame.signal === 'runner-state' && typeof data.runnerId === 'string') {
    runners[data.runnerId] = data as unknown as RunnerStatusDto;
  }
}

function start(): void {
  nowMs.value = Date.now();
  users += 1;
  if (users > 1) return;
  off = onSignal(STATUS_PATH, onFrame);
  void subscribeSignals(STATUS_PATH);
  timer = setInterval(() => {
    nowMs.value = Date.now();
  }, TICK_MS);
}

function stop(): void {
  users = Math.max(0, users - 1);
  if (users > 0) return;
  if (off) {
    off();
    off = null;
  }
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  void unsubscribeSignals(STATUS_PATH);
}

/** Takes the live state that came with the project list as the start state. */
function seed(projects: ProjectSummary[]): void {
  nowMs.value = Date.now();
  for (const p of projects) {
    live[p.name] = {
      state: p.liveState ?? ProjectLiveState.UNKNOWN,
      expiresAtMs: p.liveExpiresAtMs ?? null,
    };
    if (p.runner) {
      runnerIdOfProject[p.name] = p.runner.runnerId;
      runners[p.runner.runnerId] = p.runner;
    } else {
      delete runnerIdOfProject[p.name];
    }
  }
}

/** What the project is doing now; unknown when nothing vouches for it any more. */
function liveStateOf(project: string): ProjectLiveState {
  const entry = live[project];
  if (!entry || expired(entry.expiresAtMs)) return ProjectLiveState.UNKNOWN;
  return entry.state;
}

/** The runner serving the project, if one is bound; its phase is unknown once the entry ran out. */
function runnerOf(project: string): { runnerId: string; phase: RunnerPhase; restarts: number } | null {
  const runnerId = runnerIdOfProject[project];
  if (!runnerId) return null;
  const entry = runners[runnerId];
  const valid = entry && !expired(entry.expiresAtMs);
  return {
    runnerId,
    phase: valid ? entry.phase : RunnerPhase.UNKNOWN,
    restarts: valid ? (entry.restarts ?? 0) : 0,
  };
}

/**
 * Shared live status of the tenant's projects. Call {@link start} when a view mounts and
 * {@link stop} when it unmounts; the subscription lives as long as any view uses it.
 */
export function useProjectStatus(): {
  seed: typeof seed;
  start: typeof start;
  stop: typeof stop;
  liveStateOf: typeof liveStateOf;
  runnerOf: typeof runnerOf;
  now: ComputedRef<number>;
} {
  return { seed, start, stop, liveStateOf, runnerOf, now: computed(() => nowMs.value) };
}
