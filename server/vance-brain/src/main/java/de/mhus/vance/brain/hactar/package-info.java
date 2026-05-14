/**
 * Runtime side of the Hactar workflow subsystem — workflow resolver,
 * journal service, state projector, claim/timer/reclaim scanners,
 * type-executors, completion-event-bus and per-type completion
 * listeners.
 *
 * <p>Persistence layer (Mongo {@code @Document} types,
 * {@link de.mhus.vance.shared.hactar.HactarJournalEntry},
 * {@link de.mhus.vance.shared.hactar.HactarTaskDocument},
 * {@link de.mhus.vance.shared.hactar.HactarTimerDocument},
 * typed {@code JournalRecord} subclasses) lives in
 * {@code vance-shared/hactar}.
 *
 * <p>Wire-contract DTOs and enums are in {@code vance-api/hactar}.
 *
 * <p>See {@code planning/workflow-service.md} for the full design.
 */
@NullMarked
package de.mhus.vance.brain.hactar;

import org.jspecify.annotations.NullMarked;
