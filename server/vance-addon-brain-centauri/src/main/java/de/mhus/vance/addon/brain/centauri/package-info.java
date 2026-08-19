/**
 * Feeds application — the reading surface of Centauri.
 *
 * <p>Holds no knowledge about sources. Which protocols exist, how they page and
 * what they can filter lives in {@code de.mhus.vance.brain.centauri}; this addon
 * asks {@code CentauriService} for a page and renders it. The split is the same
 * one Zarniwoop has: capability in the brain, surface in the addon.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.addon.brain.centauri;

import org.jspecify.annotations.NullMarked;
