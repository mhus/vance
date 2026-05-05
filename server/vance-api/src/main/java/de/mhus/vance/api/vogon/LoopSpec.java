package de.mhus.vance.api.vogon;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Loop primitive. A loop phase has no worker / checkpoint / scorer of
 * its own — it iterates {@link #subPhases} until the {@link #until}
 * gate is satisfied or {@link #maxIterations} is hit.
 *
 * <p>The {@code <loopName>_max_iterations_reached} flag is set
 * automatically when the counter reaches {@link #maxIterations}; loop
 * gates that want to short-circuit on max-iterations should include
 * that flag in their {@code requiresAny} list.
 *
 * <p>See {@code specification/vogon-engine.md} §2.4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoopSpec {

    /** Loop-exit gate. Same shape as {@link GateSpec} — evaluated after
     *  each iteration's last sub-phase. */
    private @Nullable GateSpec until;

    /** Iteration cap. Default 1 = run sub-phases once (degenerate case
     *  but useful for parametrised strategies). */
    @Builder.Default
    private int maxIterations = 1;

    /** What to do when the cap is hit without {@link #until} satisfied.
     *  See {@link OnMaxReached}. */
    @Builder.Default
    private OnMaxReached onMaxReached = OnMaxReached.ESCALATE;

    /** Phases inside the loop body. Run in declared order on every
     *  iteration. {@code workerProcessIds} for these phases are
     *  invalidated on every re-entry so each iteration spawns fresh
     *  workers. */
    @Builder.Default
    private List<PhaseSpec> subPhases = new ArrayList<>();

    /**
     * Reaction when {@link #maxIterations} is reached without the
     * {@code until} gate satisfied.
     *
     * <ul>
     *   <li>{@link #ESCALATE} — fire the {@code loopExhausted} trigger
     *       on the strategy's {@code escalation:} block. If no rule
     *       matches, fall back to {@code notifyParent BLOCKED}.</li>
     *   <li>{@link #EXIT_OK} — leave the loop normally; strategy
     *       continues with the phase after the loop.</li>
     *   <li>{@link #EXIT_FAIL} — set {@code <loopName>_failed} and
     *       leave the loop; the post-loop logic decides whether to
     *       fail the strategy.</li>
     * </ul>
     */
    public enum OnMaxReached { ESCALATE, EXIT_OK, EXIT_FAIL }
}
