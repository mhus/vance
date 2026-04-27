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
 * shape. v1 uses {@code currentPhaseName} (linear phase list); v2
 * will switch to {@code currentPhasePath} for nested loops/forks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyState {

    /** Name of the strategy snapshotted at spawn time. */
    private String strategy = "";
    private String strategyVersion = "1";

    /** Current phase by name. {@code null} when the strategy has
     *  finished (process is on its way to DONE). */
    private @Nullable String currentPhaseName;

    /** Names of phases already completed. Audit + ${phases.X.…}
     *  substitution lookup. */
    @Builder.Default
    private java.util.List<String> phaseHistory = new java.util.ArrayList<>();

    /** Strategy-wide flags. Sources:
     *  <ul>
     *    <li>{@code <phase>.completed} / {@code <phase>.failed} —
     *        worker DONE/FAILED transitions.</li>
     *    <li>Checkpoint answers under {@code storeAs} key.</li>
     *  </ul>
     */
    @Builder.Default
    private Map<String, Object> flags = new LinkedHashMap<>();

    /** Per-phase loop counter (v2 — currently unused, included so
     *  v1 state docs are forward-compatible with v2 readers). */
    @Builder.Default
    private Map<String, Integer> loopCounters = new LinkedHashMap<>();

    /** Mongo-id of the worker process spawned for each
     *  worker-phase. Keys are phase names. */
    @Builder.Default
    private Map<String, String> workerProcessIds = new LinkedHashMap<>();

    /** Per-phase artifact storage — typically the worker's last
     *  reply text under key {@code "result"}. */
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
