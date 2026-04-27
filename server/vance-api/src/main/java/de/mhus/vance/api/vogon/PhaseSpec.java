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
 *   <li>Pure gate evaluation — neither worker nor checkpoint set,
 *       just {@link #gate} re-checked on every turn.</li>
 * </ul>
 *
 * <p>v1 supports worker- and checkpoint-phases. Loop / fork
 * primitives come in v2 (see spec §12).
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
}
