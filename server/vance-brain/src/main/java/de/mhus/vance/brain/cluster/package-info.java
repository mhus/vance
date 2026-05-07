/**
 * Cluster registry for brain pods. Wraps the persistence layer
 * ({@link de.mhus.vance.shared.cluster.BrainPodService}) with the
 * brain-side lifecycle: register on
 * {@link org.springframework.boot.context.event.ApplicationReadyEvent},
 * heartbeat every minute, mark STOPPED on shutdown.
 */
@NullMarked
package de.mhus.vance.brain.cluster;

import org.jspecify.annotations.NullMarked;
