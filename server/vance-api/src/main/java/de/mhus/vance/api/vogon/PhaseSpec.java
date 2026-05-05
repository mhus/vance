package de.mhus.vance.api.vogon;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One phase inside a strategy. A phase typically does one of:
 * <ul>
 *   <li>Spawn a worker via {@link #worker} recipe and consume the
 *       worker's DONE event (worker-phase).</li>
 *   <li>Pause and ask the user via {@link #checkpoint}
 *       (checkpoint-phase).</li>
 *   <li>Iterate sub-phases via {@link #loop} until a gate is
 *       satisfied (loop-phase, see {@link LoopSpec}).</li>
 *   <li>Pure gate evaluation — neither worker nor checkpoint set,
 *       just {@link #gate} re-checked on every turn.</li>
 * </ul>
 *
 * <p>A worker-phase may additionally carry exactly one of
 * {@link #scorer} or {@link #decider} to extract a structured
 * decision from the worker reply. {@link #scorer}/{@link #decider}
 * are mutually exclusive — strategy-load validation rejects mixing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhaseSpec {

    /** Unique phase name within the strategy. */
    private String name = "";

    /** Optional recipe to spawn as a worker. May contain
     *  {@code ${params.X}} variable substitutions. */
    private @Nullable String worker;

    /** Initial steer-content sent to the worker. May contain
     *  {@code ${…}} substitutions. */
    private @Nullable String workerInput;

    /** Optional checkpoint that runs at the end of the phase
     *  (after worker DONE, if worker is set). */
    private @Nullable CheckpointSpec checkpoint;

    /** Gate that must be satisfied before the phase counts as
     *  completed. {@code null} = "no gate beyond the phase's
     *  intrinsic activity". */
    private @Nullable GateSpec gate;

    /** Loop-phase body. When non-null this is a loop-phase and
     *  {@link #worker}/{@link #checkpoint}/{@link #scorer}/{@link #decider}
     *  must all be null. */
    private @Nullable LoopSpec loop;

    /** Score-based switch evaluated after worker DONE. Mutually
     *  exclusive with {@link #decider}. */
    private @Nullable ScorerSpec scorer;

    /** Categorical match evaluated after worker DONE. Mutually
     *  exclusive with {@link #scorer}. */
    private @Nullable DeciderSpec decider;
}
