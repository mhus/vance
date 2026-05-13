/**
 * Wire-contract DTOs for the generic event log — append-only stream of
 * trigger lifecycle events (scheduler today, webhooks and hooks later).
 *
 * <p>Source identifiers follow the convention {@code "<kind>:<id>"} —
 * e.g. {@code "scheduler:morning-briefing"} or future {@code "webhook:gh-push"}.
 * See {@code specification/scheduler.md} §7.
 */
@NullMarked
package de.mhus.vance.api.eventlog;

import org.jspecify.annotations.NullMarked;
