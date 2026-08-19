/**
 * Centauri — the consumption side of foreign time-ordered streams.
 *
 * <p>Named after Alpha Centauri, where the demolition plans had "been
 * available in the local planning office for fifty of your Earth years":
 * information that was there and that nobody read.
 *
 * <p>Centauri does not maintain sources. Feed lists, full-text fetching,
 * categorisation and translation stay with the source — Hrafnagud via the
 * ode contract, a Mastodon instance, later others. This package holds the
 * dispatcher, the per-project instance factory, the gate and the merge;
 * the wire-neutral contract lives in {@code de.mhus.vance.toolpack.feed},
 * mirroring how {@code zarniwoop} relates to {@code toolpack.research}.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.brain.centauri;

import org.jspecify.annotations.NullMarked;
