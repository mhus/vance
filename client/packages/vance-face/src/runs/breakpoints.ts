import { RunStatus } from '@vance/generated';

/**
 * The breakpoint set after the user clicks a state: the state is added at the
 * end, or taken out when it is already there. The server wants the whole set,
 * so the view always computes "the set as it should be now" and sends that.
 */
export function toggleBreakpoint(current: readonly string[], state: string): string[] {
  return current.includes(state)
    ? current.filter((name) => name !== state)
    : [...current, state];
}

/**
 * Whether breakpoints can still be changed on a run in this status. An ended
 * run has no future states to halt before; every other status — including a
 * halted or waiting one — still has.
 */
export function canChangeBreakpoints(status: RunStatus | undefined): boolean {
  return status === RunStatus.RUNNING
    || status === RunStatus.PAUSED
    || status === RunStatus.WAITING
    || status === RunStatus.STOPPING;
}
