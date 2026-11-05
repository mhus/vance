import { RunStatus } from '@vance/generated';

/** How often an open, still-moving run is read again. */
export const RUN_POLL_INTERVAL_MS = 2000;

/**
 * The first re-read comes sooner than the regular beat. The answer to a click
 * (a debug step, say) is the state at that instant — usually still "running" —
 * and waiting a full beat for the real outcome makes every step feel slow.
 */
export const RUN_FIRST_POLL_MS = 500;

/**
 * Whether the run is still moving by itself. Such a run changes under the
 * reader's eyes — a debug step runs a state and halts again a moment later —
 * so the view reads it again instead of showing the moment of the click.
 * A run that waits for something (a person, a pause, a halt) does not move on
 * its own; its buttons are the way forward, and each button returns the fresh
 * state.
 */
export function isMoving(status: RunStatus | undefined): boolean {
  return status === RunStatus.RUNNING || status === RunStatus.STOPPING;
}
