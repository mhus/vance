/**
 * Manuals — recipe-configurable how-to documentation tools.
 *
 * <p>{@link de.mhus.vance.brain.tools.manual.ManualListTool} and
 * {@link de.mhus.vance.brain.tools.manual.ManualReadTool} read a list
 * of folder paths from {@code params.manualPaths} on the running
 * {@code ThinkProcessDocument} and union the documents found at those
 * paths through the regular document cascade
 * (project → {@code _vance} → classpath:vance-defaults). This replaces
 * the engine-specific {@code docs_*} / {@code eddie_docs_*} tool pairs:
 * one tool name, multiple sources, recipe-configured per engine and
 * per connection profile.
 *
 * <p>See {@code specification/recipes.md} §6a (Profiles) for how
 * {@code manualPaths} interacts with profile-blocks.
 */
@NullMarked
package de.mhus.vance.brain.tools.manual;

import org.jspecify.annotations.NullMarked;
