/**
 * Runtime side of the Magrathea workflow subsystem — workflow resolver,
 * journal service, state projector, claim/timer/reclaim scanners,
 * type-executors, completion-event-bus and per-type completion
 * listeners.
 *
 * <p>Persistence layer (Mongo {@code @Document} types,
 * {@link de.mhus.vance.shared.magrathea.MagratheaJournalEntry},
 * {@link de.mhus.vance.shared.magrathea.MagratheaTaskDocument},
 * {@link de.mhus.vance.shared.magrathea.MagratheaTimerDocument},
 * typed {@code JournalRecord} subclasses) lives in
 * {@code vance-shared/magrathea}.
 *
 * <p>Wire-contract DTOs and enums are in {@code vance-api/magrathea}.
 *
 * <p>See {@code planning/workflow-service.md} for the full design.
 */
@NullMarked
package de.mhus.vance.brain.magrathea;

import org.jspecify.annotations.NullMarked;
