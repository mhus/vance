/**
 * Cross-side execution registry.
 *
 * <p>Brain-central, in-memory index of every shell execution running
 * either on this brain pod ({@link
 * de.mhus.vance.brain.tools.exec.ExecManager}) or on a connected foot
 * client. The registry itself does not own any process — it only knows
 * about them. Steering operations (kill, stat, tail) route through the
 * existing tool pipe to whichever side owns the job.
 *
 * <p>Foot-job entries arrive via {@code EXEC_EVENT} frames pushed from
 * the foot (added separately); brain-job entries are written directly
 * by {@code ExecManager} as it spawns and updates jobs.
 */
@NullMarked
package de.mhus.vance.brain.execution;

import org.jspecify.annotations.NullMarked;
