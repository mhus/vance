/**
 * Search application — a surface for people over Zarniwoop.
 *
 * <p>The dispatcher, the eight providers, the modalities and the curated
 * pipeline all live in {@code de.mhus.vance.brain.zarniwoop}. This addon adds no
 * search capability whatsoever; it adds a way to reach the existing one without
 * asking a model to call a tool. The split is the same one Centauri has:
 * capability in the brain, surface in the addon.
 *
 * <p><b>No LLM tools here.</b> The {@code research_*} family is complete — an
 * agent already has a tool for everything this app does. Adding a second set
 * would put two names on one capability.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.addon.brain.zarniwoop;

import org.jspecify.annotations.NullMarked;
