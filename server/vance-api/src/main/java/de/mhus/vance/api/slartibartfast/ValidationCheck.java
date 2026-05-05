package de.mhus.vance.api.slartibartfast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One result entry from a Slartibartfast validator pass. Multiple
 * checks accumulate into {@link ArchitectState#getValidationReport()}.
 * The validator turns failed checks into the corrective hint sent
 * back to the planner LLM for re-prompt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationCheck {

    /** Stable identifier of the rule that produced this check —
     *  e.g. {@code "every-subgoal-has-evidence-or-marked-speculative"},
     *  {@code "no-dangling-claim-refs"}, {@code "speculation-bound"}. */
    private String rule = "";

    /** {@code true} = constraint holds, {@code false} = violation. */
    private boolean passed;

    /** Optional id of the offending element (subgoal id, claim id,
     *  recipe path, …) — gives the re-prompt hint enough context
     *  to localise the fix. {@code null} for global checks like
     *  speculation-bound. */
    private @Nullable String offendingId;

    /** Human-readable explanation of the failure (or success)
     *  suitable for inclusion in the LLM corrective hint. */
    private String message = "";
}
