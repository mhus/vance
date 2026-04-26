/**
 * Engine-execution lanes.
 *
 * <p>{@link de.mhus.vance.brain.scheduling.LaneScheduler} replaces the
 * "engine runs synchronously on the WS-frame thread" pattern: callers
 * submit a unit of engine work for a {@code laneId} (typically a
 * think-process id), get back a {@link java.util.concurrent.CompletableFuture}
 * to await, and the scheduler runs the work on a virtual thread —
 * <em>serially per lane</em> so two messages targeting the same
 * process can't race on the engine's mutable state.
 *
 * <p>Across lanes, work runs in parallel up to the bounds of the
 * underlying virtual-thread pool. The receive thread of the
 * WebSocket is never blocked by an engine call, which prevents the
 * self-lock that triggered when an engine awaited a {@code
 * client-tool-result} on the same socket it was holding open.
 */
@NullMarked
package de.mhus.vance.brain.scheduling;

import org.jspecify.annotations.NullMarked;
