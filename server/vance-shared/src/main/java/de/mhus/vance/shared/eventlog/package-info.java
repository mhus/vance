/**
 * Generic append-only event log — records lifecycle ticks of any trigger
 * source (scheduler today, webhooks and hooks later). The collection
 * shape is intentionally schema-light so additional producers can use it
 * without migrations.
 *
 * <p>See {@code specification/scheduler.md} §7.
 */
@NullMarked
package de.mhus.vance.shared.eventlog;

import org.jspecify.annotations.NullMarked;
