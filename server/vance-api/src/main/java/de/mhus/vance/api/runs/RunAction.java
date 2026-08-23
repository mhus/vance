package de.mhus.vance.api.runs;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What can be done to a run. Reported per run and per moment, not per
 * source — a finished run offers nothing regardless of what produced it,
 * and a source that cannot stop anything yet simply reports an empty set.
 *
 * <p>All three concern execution; the record of a run always survives.
 * Deleting runs is deliberately not offered — for a journal-backed run it
 * would erase the audit trail the design exists to keep
 * ({@code planning/runs-view.md} §9).
 *
 * <p>All three sources answer with real verbs, derived from the run's
 * current state — the per-source matrix is in
 * {@code specification/public/runs-view.md} §6. Performing one is
 * idempotent everywhere: an action the run does not currently offer is a
 * logged no-op, not an error, because the button was rendered from a
 * snapshot the run may have moved on from.
 */
@GenerateTypeScript("runs")
public enum RunAction {
    /** Start nothing new; work in flight finishes. */
    PAUSE,
    /** Undo a {@link #PAUSE} — and only that. */
    RESUME,
    /** Pause, unwind what can be unwound, mark it ended. */
    STOP
}
