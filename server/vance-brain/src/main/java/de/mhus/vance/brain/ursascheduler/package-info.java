/**
 * Runtime side of the scheduler subsystem — Spring {@code TaskScheduler}
 * registration, overlap-policy handling, system-session resolution,
 * process-lifecycle hooks. YAML parsing and the document-layer cascade
 * live in {@code vance-shared/ursascheduler}.
 *
 * <p>See {@code specification/scheduler.md}.
 */
@NullMarked
package de.mhus.vance.brain.ursascheduler;

import org.jspecify.annotations.NullMarked;
