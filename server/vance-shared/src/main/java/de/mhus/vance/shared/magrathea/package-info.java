/**
 * Persistence layer for the Magrathea workflow subsystem — MongoDB
 * {@code @Document} types for the three Magrathea collections
 * ({@code magrathea_journal}, {@code magrathea_tasks}, {@code magrathea_timers}),
 * the workflow YAML loader, and supporting repositories.
 *
 * <p>Runtime orchestration (claim-scanner, completion-event-bus,
 * type-executors, listeners) lives in {@code vance-brain/magrathea}.
 *
 * <p>See {@code planning/workflow-service.md}.
 */
@NullMarked
package de.mhus.vance.shared.magrathea;

import org.jspecify.annotations.NullMarked;
