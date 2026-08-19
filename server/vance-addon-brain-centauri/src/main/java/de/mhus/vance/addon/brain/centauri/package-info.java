/**
 * Feeds application — the reading surface of Centauri.
 *
 * <p>The dispatcher, the merge and the cursor live in
 * {@code de.mhus.vance.brain.centauri} — this addon asks {@code CentauriService}
 * for a page and renders it. The split is the same one Zarniwoop has: capability
 * in the brain, surface in the addon.
 *
 * <p>The one deliberate exception is {@code .protocols}: only {@code ode}, the
 * contract itself, is built into the brain. The two example sources (USGS,
 * Wikipedia) ship here, so an installation that does not want two demo sources
 * in its core simply does not load this addon.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.addon.brain.centauri;

import org.jspecify.annotations.NullMarked;
