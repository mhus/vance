import { RunStatus, type RunStepDto } from '@vance/generated';
import type { GraphOverlay, GraphStateTone } from '@/workflowGraph/overlay';

/**
 * Outcomes that mean a state did not do what it was for. Everything else —
 * success, approved, fired, or a branch a plan chose on purpose — counts as
 * done. A name ending in `_error` is an error kind by convention.
 */
const FAILED_OUTCOMES = new Set([
  'failure', 'timeout', 'cancelled', 'rejected', 'human_rejected',
  'undecidable', 'insufficient_info', 'capability_missing',
]);

/** Statuses in which the latest step without an outcome is still in front of the run. */
const ACTIVE: ReadonlySet<RunStatus> = new Set<RunStatus>([
  RunStatus.RUNNING, RunStatus.WAITING, RunStatus.PAUSED, RunStatus.STOPPING,
]);

/** Whether an outcome means the state did not do what it was for. */
export function isFailureOutcome(outcome: string): boolean {
  return FAILED_OUTCOMES.has(outcome) || outcome.endsWith('_error');
}

function toneOfOutcome(outcome: string): GraphStateTone {
  return isFailureOutcome(outcome) ? 'other' : 'ok';
}

/**
 * What a run did, state by state, in the form the graph draws.
 *
 * <p>A state entered several times (a loop) keeps its latest outcome and a
 * count. The step without an outcome at the end of an active run is where the
 * run stands: running, or — when the run is paused — halted in front of it.
 */
export function buildOverlay(steps: RunStepDto[] | undefined, status: RunStatus): GraphOverlay {
  const overlay: GraphOverlay = {};
  if (!steps) return overlay;
  steps.forEach((step, index) => {
    const previous = overlay[step.name];
    const entries = (previous?.entries ?? 0) + 1;
    const isLast = index === steps.length - 1;
    if (!step.outcome) {
      const standing = isLast && ACTIVE.has(status);
      overlay[step.name] = {
        // An entered state of a run that ended without an outcome (stopped
        // mid-state) is neither current nor done; show it as not completed.
        tone: standing ? (status === RunStatus.PAUSED ? 'halted' : 'current') : 'other',
        entries,
      };
      return;
    }
    overlay[step.name] = {
      tone: toneOfOutcome(step.outcome),
      entries,
      outcome: step.outcome,
      durationMs: step.durationMs ?? undefined,
      simulated: step.simulated || undefined,
    };
  });
  return overlay;
}
