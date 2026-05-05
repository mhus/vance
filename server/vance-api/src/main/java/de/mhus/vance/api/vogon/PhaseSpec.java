package de.mhus.vance.api.vogon;

import java.util.List;
import java.util.Map;
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

    /**
     * Phase J — JSON schema (small subset; see
     * {@code de.mhus.vance.shared.util.JsonSchemaLight}) the worker's
     * structured reply must satisfy before {@link #postActions} run.
     * On schema violation the engine re-prompts the worker up to
     * {@link #getMaxOutputCorrections()} times, then fails the phase.
     *
     * <p>Replaces the hand-written reply for executive worker
     * patterns: instead of telling the LLM "now call doc_create_text",
     * the recipe says "produce a JSON with {chapterText, slug}", and
     * the engine deterministically persists via post-actions.
     */
    @Builder.Default
    private @Nullable Map<String, Object> outputSchema = null;

    /**
     * Phase J — deterministic actions executed after worker DONE
     * (and after {@link #outputSchema} validation, when set).
     * Substitutions over the worker's parsed output via
     * {@code ${output.X}} and the strategy state via
     * {@code ${params.X}} / {@code ${flags.X}} / {@code ${phases.X.…}}.
     *
     * <p>Vocabulary: see {@link BranchAction} sealed hierarchy
     * (Doc-actions: {@link BranchAction.DocCreateText},
     * {@link BranchAction.DocCreateKind},
     * {@link BranchAction.ListAppend},
     * {@link BranchAction.DocConcat},
     * {@link BranchAction.InboxPost}; plus the existing flow-control
     * actions like {@link BranchAction.SetFlag}).
     */
    @Builder.Default
    private @Nullable List<BranchAction> postActions = null;

    /** Re-prompt cap when the worker reply doesn't validate against
     *  {@link #outputSchema}. Default 2 (matches scorer/decider). */
    @Builder.Default
    private @Nullable Integer maxOutputCorrections = null;
}
