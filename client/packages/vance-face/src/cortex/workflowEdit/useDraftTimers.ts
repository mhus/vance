import { onBeforeUnmount } from 'vue';

/**
 * The pause between typing and writing: a form control schedules its write,
 * typing again reschedules it, and while one is pending the control must not be
 * overwritten with what the document still holds. Keyed by a slot name that
 * says whose control it is, so a pending write for one state is not mistaken
 * for another's. Pending writes are dropped when the form goes away.
 */
export function useDraftTimers(delayMs = 500) {
  const timers = new Map<string, ReturnType<typeof setTimeout>>();

  onBeforeUnmount(() => {
    timers.forEach((timer) => clearTimeout(timer));
    timers.clear();
  });

  return {
    /** Whether a write for {@code slot} is waiting. */
    pending(slot: string): boolean {
      return timers.has(slot);
    },
    /** Run {@code write} after the pause, replacing a write already waiting for the slot. */
    schedule(slot: string, write: () => void): void {
      clearTimeout(timers.get(slot));
      timers.set(slot, setTimeout(() => {
        timers.delete(slot);
        write();
      }, delayMs));
    },
    /** Drop a waiting write. */
    cancel(slot: string): void {
      clearTimeout(timers.get(slot));
      timers.delete(slot);
    },
  };
}
