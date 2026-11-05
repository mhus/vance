package de.mhus.vance.shared.magrathea.journal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Switches a debug run between stepping and running free.
 *
 * <p>A run started in debug mode stands before each state until it is
 * stepped. {@code stepping=false} (written when such a run is resumed) lets it
 * run on without halting; {@code stepping=true} (written by a step) puts the
 * halt back. The latest record wins; without one, a debug run is stepping.
 *
 * <p>A journal record rather than a flag on the task or the run row, so the
 * mode is part of the run's audit trail and is read the same way as every
 * other piece of run state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebugRecord implements JournalRecord {

    /** {@code true}: halt before the next state. {@code false}: run on. */
    private boolean stepping;
}
