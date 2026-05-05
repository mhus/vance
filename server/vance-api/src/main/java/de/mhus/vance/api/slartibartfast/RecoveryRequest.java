package de.mhus.vance.api.slartibartfast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A validator's request to roll back to an earlier phase with a
 * concrete corrective hint. Replaces the hardcoded
 * BINDING↔DECOMPOSING / VALIDATING↔PROPOSING pairs with a generic
 * "any phase can request any earlier phase" mechanism — needed
 * because real validation discoveries can require deeper rollback
 * (e.g. VALIDATING discovers a claim was misclassified → request
 * CLASSIFYING re-run, not just PROPOSING re-prompt).
 *
 * <p>The engine consults {@link ArchitectState#getPendingRecovery()}
 * at the start of every {@code runTurn}. A non-null request flips
 * {@link ArchitectState#getStatus()} to {@link #toPhase()} (clearing
 * downstream output) and clears itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryRequest {

    /** Phase that detected the problem and is initiating the
     *  rollback. Logged for the iteration audit trail. */
    @Builder.Default
    private ArchitectStatus fromPhase = ArchitectStatus.VALIDATING;

    /** Phase to re-enter. Must be earlier in the lifecycle than
     *  {@link #fromPhase}; the engine rejects forward-only or
     *  same-phase requests. */
    @Builder.Default
    private ArchitectStatus toPhase = ArchitectStatus.PROPOSING;

    /** Short human-readable rule-id of the validation check that
     *  triggered the rollback (e.g.
     *  {@code "no-dangling-claim-refs"},
     *  {@code "speculation-bound"},
     *  {@code "criterion-not-addressed"}). */
    private String reason = "";

    /** Concrete corrective hint to be appended to the next
     *  iteration's LLM prompt — same role as Marvin-PLAN's
     *  re-prompt hint after a {@code validatePlanChildren}
     *  violation. */
    private String hint = "";

    /** Optional id of the offending element from
     *  {@link ValidationCheck#getOffendingId()} — gives the
     *  re-prompt enough localisation context to fix the right
     *  thing. */
    private @Nullable String offendingId;
}
