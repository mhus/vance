package de.mhus.vance.api.vogon;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mutable runtime state of a Vogon process. Persisted on
 * {@code ThinkProcessDocument.engineParams.strategyState}; restored
 * verbatim on resume so a Brain restart picks up where the lane
 * left off.
 *
 * <p>See {@code specification/vogon-engine.md} §6 for the full
 * shape. The current phase is addressed via
 * {@link #currentPhasePath} — a stack with one segment per nesting
 * level (linear phases have a one-element path, loop sub-phases
 * carry {@code [<loopName>, <subPhaseName>]}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyState {

    /** Name of the strategy snapshotted at spawn time. */
    private String strategy = "";
    private String strategyVersion = "1";

    /** Path stack to the active phase. Each segment is a phase name
     *  on its level (top-level for the outermost, sub-phase under a
     *  loop for nested levels). Empty list ⇔ strategy finished and
     *  on its way to DONE. */
    @Builder.Default
    private java.util.List<String> currentPhasePath = new java.util.ArrayList<>();

    /** Qualified names of phases already completed. Linear phases
     *  appear as their bare name, loop sub-phases as
     *  {@code <loopName>/<subPhaseName>}. Audit + {@code ${phases.X.…}}
     *  substitution lookup. */
    @Builder.Default
    private java.util.List<String> phaseHistory = new java.util.ArrayList<>();

    /** Strategy-wide flags. Sources:
     *  <ul>
     *    <li>{@code <phase>_completed} / {@code <phase>_failed} —
     *        worker DONE/FAILED transitions.</li>
     *    <li>{@code <loopName>_max_iterations_reached} — loop counter
     *        cap reached.</li>
     *    <li>Checkpoint answers + scorer/decider results under
     *        {@code storeAs} key (and per-field sub-keys).</li>
     *    <li>{@code setFlag} branch actions.</li>
     *  </ul>
     */
    @Builder.Default
    private Map<String, Object> flags = new LinkedHashMap<>();

    /** Per-loop iteration counter. Keyed by loop-phase name. Set to
     *  1 on first entry, incremented on every re-entry. */
    @Builder.Default
    private Map<String, Integer> loopCounters = new LinkedHashMap<>();

    /** Mongo-id of the worker process spawned for each worker-phase.
     *  Keys are qualified phase names ({@code <loopName>/<subPhaseName>}
     *  for loop sub-phases). Re-entry into a loop invalidates these
     *  for the loop body so the next iteration spawns fresh workers. */
    @Builder.Default
    private Map<String, String> workerProcessIds = new LinkedHashMap<>();

    /** Per-phase artifact storage — typically the worker's last
     *  reply text under key {@code "result"} plus an optional
     *  {@code "scorerOutput"}/{@code "deciderOutput"} entry. Keys are
     *  qualified phase names like {@link #workerProcessIds}. */
    @Builder.Default
    private Map<String, Map<String, Object>> phaseArtifacts = new LinkedHashMap<>();

    /** When non-null, Vogon is BLOCKED on a checkpoint answer.
     *  Set when a checkpoint inbox-item is created; cleared when
     *  the answer arrives. */
    private @Nullable PendingCheckpoint pendingCheckpoint;

    /** Marks the strategy as "all phases done"; runTurn finalizes
     *  the process to DONE on the next pass. */
    private boolean strategyComplete;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingCheckpoint {
        private String phaseName = "";
        private String inboxItemId = "";
        private CheckpointType type = CheckpointType.APPROVAL;
        private @Nullable String storeAs;
    }
}
