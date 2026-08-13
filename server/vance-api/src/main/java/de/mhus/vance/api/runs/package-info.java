/**
 * Wire contract for the run view — one vocabulary for the instances of
 * every runtime that produces them: Magrathea workflow runs, plan-shaped
 * ThinkProcesses (Vogon, Marvin) and Damogran compose runs.
 *
 * <p>A run is the instance; the thing it runs (a workflow YAML, a
 * strategy, a compose manifest) is the definition and lives elsewhere.
 * These types describe instances only.
 *
 * <p>Design: {@code planning/runs-view.md}. Served under
 * {@code /brain/{tenant}/runs}.
 */
@NullMarked
package de.mhus.vance.api.runs;

import org.jspecify.annotations.NullMarked;
