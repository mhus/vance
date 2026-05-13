/**
 * Timer-driven self-wakeup primitive.
 *
 * <p>{@link de.mhus.vance.brain.wakeup.WakeupRegistry} lets a process
 * schedule a {@code ProcessEvent(SCHEDULED_WAKEUP)} to be delivered
 * back to its own inbox after a delay. Engines use it via the
 * {@code wakeup_in} / {@code wakeup_cancel} tools to build heartbeats,
 * polling loops, and long-running-job watchdogs without an external
 * scheduler.
 *
 * <p>State is in-memory per pod. Wakeups do not survive a pod restart
 * — see {@code planning/wakeup-and-exec.md} §6/§7 for the rationale
 * (exec-jobs are pod-affine anyway, so persistent timers without
 * persistent subprocesses would be inconsistent).
 */
@NullMarked
package de.mhus.vance.brain.wakeup;

import org.jspecify.annotations.NullMarked;
