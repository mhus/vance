/**
 * Process-history search support: marker-tag computation and the search /
 * recall tools that let an engine look up past turns even after they have
 * been rolled into a compaction memory.
 *
 * <p>See {@code planning/process-history-search.md} for the overall
 * design. {@link de.mhus.vance.brain.history.HistoryTagBuilder} is a
 * pure-functional component the tool dispatcher uses to translate a
 * successful (or failed) tool invocation into a set of marker tags.
 */
@NullMarked
package de.mhus.vance.brain.history;

import org.jspecify.annotations.NullMarked;
