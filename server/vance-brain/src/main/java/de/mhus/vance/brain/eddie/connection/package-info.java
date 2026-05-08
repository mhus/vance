/**
 * Eddie's outbound Working-WS plumbing — used to talk to a worker
 * project's Arthur (or other engine) in another project (potentially
 * on another brain pod). Eddie acts as a regular WS client against the
 * worker pod's {@code /ws} endpoint with {@code profile=eddie} so the
 * worker treats her like any other connected client, plus the
 * profile-specific tool/feature filters apply (see
 * {@code engine-message-routing.md} §4.1.1).
 *
 * <p>Three pieces:
 * <ul>
 *   <li>{@link EddieWorkerConnection} — single Working-WS to one
 *       worker session.</li>
 *   <li>{@link EddieWorkerConnectionPool} — per-Eddie-process
 *       collection, keyed by {@code workerProcessId}, with reconnect
 *       support driven by {@code WorkerLinkSnapshot}.</li>
 *   <li>{@link EddieFrameRouter} — classifies incoming frames and
 *       dispatches them to the triage / plan-mirror handlers.</li>
 * </ul>
 *
 * <p>See {@code specification/eddie-engine.md} §8 +
 * {@code specification/engine-message-routing.md} §5.
 */
@NullMarked
package de.mhus.vance.brain.eddie.connection;

import org.jspecify.annotations.NullMarked;
