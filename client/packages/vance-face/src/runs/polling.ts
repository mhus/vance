import { RunStatus } from '@vance/generated';

/** How often an open run that is still moving is read again. */
export const RUN_POLL_INTERVAL_MS = 2000;

/**
 * The first re-read of a moving run comes sooner than the regular beat. The
 * answer to a click (a debug step, say) is the state at that instant — usually
 * still "running" — and waiting a full beat for the real outcome makes every
 * step feel slow.
 */
export const RUN_FIRST_POLL_MS = 500;

/**
 * How often a paused run is read. A pause starts nothing new, but what was in
 * flight still finishes and a timer still fires — the picture changes without
 * anybody clicking. Nothing is racing the reader, so a slower beat is enough.
 */
export const PAUSED_POLL_INTERVAL_MS = 5000;

export interface PollPlan {
  /** Delay of the first re-read, or null to wait for the first regular beat. */
  firstMs: number | null;
  /** Beat of the regular re-reads. */
  everyMs: number;
}

/**
 * When to read an open run again, or null when it needs no reading.
 *
 * A run that works or is being stopped changes under the reader's eyes and is
 * read quickly. A paused one is read slowly. A run that waits for a person has
 * a button (or an inbox item) as the way forward, and an ended run does not
 * change at all.
 */
export function pollPlan(status: RunStatus | undefined): PollPlan | null {
  if (status === RunStatus.RUNNING || status === RunStatus.STOPPING) {
    return { firstMs: RUN_FIRST_POLL_MS, everyMs: RUN_POLL_INTERVAL_MS };
  }
  if (status === RunStatus.PAUSED) {
    return { firstMs: null, everyMs: PAUSED_POLL_INTERVAL_MS };
  }
  return null;
}
