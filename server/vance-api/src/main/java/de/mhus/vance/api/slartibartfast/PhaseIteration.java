package de.mhus.vance.api.slartibartfast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One pass through one phase of the lifecycle. Replaces the flat
 * {@code retryCount} fields with structured iteration history so
 * the audit can show <em>how</em> the planner converged — not just
 * that it did.
 *
 * <p>One {@link PhaseIteration} per phase-entry: a clean linear run
 * appends N entries (one per phase). A phase that re-runs after a
 * {@link RecoveryRequest} appends an additional entry tagged with
 * the triggering rule. A successful phase has
 * {@link #getOutcome()} = {@link IterationOutcome#PASSED}; a phase
 * that triggered rollback has {@link IterationOutcome#REQUESTED_RECOVERY}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhaseIteration {

    public enum IterationOutcome {
        /** Phase produced its output and the gate validated. */
        PASSED,

        /** Phase finished but a downstream gate wants a rollback
         *  to <em>this</em> phase. The next iteration re-runs this
         *  phase with the recovery hint. */
        REQUESTED_RECOVERY,

        /** Phase failed for a non-recoverable reason (LLM provider
         *  down, malformed JSON past retry budget). Engine
         *  transitions to {@link ArchitectStatus#FAILED}. */
        FAILED,
    }

    /** 1-based attempt counter <em>within this phase</em>. The
     *  global iteration order is reconstructed by reading
     *  {@link ArchitectState#getIterations()} in insertion order. */
    private int iteration;

    @Builder.Default
    private ArchitectStatus phase = ArchitectStatus.FRAMING;

    /** What set this iteration in motion: {@code "initial"} for
     *  the first pass, or the rule-id from a
     *  {@link RecoveryRequest#getReason()} that bounced control
     *  back here. */
    @Builder.Default
    private String triggeredBy = "initial";

    /** Short summary of what the phase had to work with on
     *  entry — e.g. {@code "3 evidence sources, 2 FACT claims"}.
     *  Useful when iteration N+1 changed inputs vs iteration N. */
    private String inputSummary = "";

    /** Short summary of what the phase produced — e.g.
     *  {@code "4 subgoals, 1 speculative"}. */
    private String outputSummary = "";

    @Builder.Default
    private IterationOutcome outcome = IterationOutcome.PASSED;

    /** Foreign key into {@link ArchitectState#getLlmCallRecords()}.
     *  {@code null} for phases that don't issue LLM calls
     *  (PERSISTING, the BINDING/VALIDATING gates themselves). */
    private @Nullable String llmCallRecordId;
}
