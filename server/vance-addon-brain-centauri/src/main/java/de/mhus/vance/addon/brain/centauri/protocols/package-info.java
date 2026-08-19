/**
 * Example feed protocols, shipped with the Feeds addon rather than the brain.
 *
 * <p>Only {@code ode} — the contract itself — is built into {@code vance-brain}.
 * These two are demonstrations: real foreign APIs that never heard of Vancetope,
 * useful for seeing a feed work without owning a source. Keeping them here means
 * an installation that does not want them simply does not load the addon,
 * instead of carrying two demo sources in the core.
 *
 * <p>They reach back into {@code de.mhus.vance.brain.centauri.protocols} for the
 * HTTP seam. That direction is fine — an addon depends on the brain — and it is
 * the reason {@code CentauriHttpClient} stayed behind.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.addon.brain.centauri.protocols;

import org.jspecify.annotations.NullMarked;
