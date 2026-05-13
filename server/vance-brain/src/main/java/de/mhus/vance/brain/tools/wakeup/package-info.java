/**
 * Tools for scheduling and cancelling self-wakeup timers.
 *
 * <p>Front for {@link de.mhus.vance.brain.wakeup.WakeupRegistry}. The
 * engine drains the {@code SCHEDULED_WAKEUP} event from its inbox in
 * a later lane-turn and decides what to do with it. See
 * {@code planning/wakeup-and-exec.md} for the use-cases (heartbeat,
 * polling, long-job watchdog).
 */
@NullMarked
package de.mhus.vance.brain.tools.wakeup;

import org.jspecify.annotations.NullMarked;
