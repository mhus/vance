package de.mhus.vance.shared.magrathea.journal;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Replaces the set of states a debuggable run halts before. The latest record
 * wins; before the first one the set on the {@link StartRecord} applies.
 *
 * <p>A journal record, like {@link DebugRecord}, so changing a breakpoint is
 * part of the run's audit trail and is read the same way as every other piece
 * of run state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BreakpointsRecord implements JournalRecord {

    /** The complete set after the change, not a delta. */
    private Set<String> breakpoints;
}
