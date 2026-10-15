/**
 * What the admin shell does to resource leases
 * ({@code specification/public/lease-system.md} §9): read holders,
 * counters and definitions straight from Mongo, and revoke over
 * {@code /internal/lease/admin} — a revoke must run the lease type's
 * handler, and the handler registry lives in the brain. The {@code --force}
 * path is the exception for a cluster without a reachable brain and says so.
 *
 * <p>Separate from {@code de.mhus.vance.anus.shell} for the same reason as
 * {@code de.mhus.vance.anus.cluster}: these are operations with outcomes a
 * caller acts on; the {@code @Command} methods turn them into text.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.lease;
