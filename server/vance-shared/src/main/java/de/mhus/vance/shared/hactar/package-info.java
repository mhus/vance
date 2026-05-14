/**
 * Persistence layer for the Hactar workflow subsystem — MongoDB
 * {@code @Document} types for the three Hactar collections
 * ({@code hactar_journal}, {@code hactar_tasks}, {@code hactar_timers}),
 * the workflow YAML loader, and supporting repositories.
 *
 * <p>Runtime orchestration (claim-scanner, completion-event-bus,
 * type-executors, listeners) lives in {@code vance-brain/hactar}.
 *
 * <p>See {@code planning/workflow-service.md}.
 */
@NullMarked
package de.mhus.vance.shared.hactar;

import org.jspecify.annotations.NullMarked;
