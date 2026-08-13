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
 * <p>v1 reports an empty set everywhere: the vocabulary is fixed now so
 * that adding the verbs later touches no DTO and no UI.
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
