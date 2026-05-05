package de.mhus.vance.api.slartibartfast;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Output of the FRAMING phase — the user's free-text request
 * re-formulated into a structured goal plus two distinct lists of
 * acceptance criteria: {@link #getStatedCriteria()} (what the user
 * said) and {@link #getAssumedCriteria()} (what the planner
 * inferred, with rationales and confidence). The split exists
 * because the failure modes are symmetric — inferring too little
 * misses obvious user intent ("write me an essay" implies "save
 * it"), inferring too much over-asserts. Keeping the two lists
 * separate lets CONFIRMING handle low-confidence inferences with
 * inbox confirmation while letting high-confidence ones pass
 * through with audit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FramedGoal {

    /** Re-formulated goal in declarative form. The planner's
     *  primary anchor — concise, scope-bounded, no ambiguity. */
    private String framed = "";

    /** Verbatim original user request, preserved for audit so a
     *  reader can reconstruct what the LLM started from. */
    private String sourceUserText = "";

    /** Criteria lifted directly from the user's text. Origin
     *  must be {@link CriterionOrigin#USER_STATED}. Implicit
     *  confidence 1.0; required to be non-empty in normal runs
     *  (an empty stated list usually means FRAMING misread the
     *  request). */
    @Builder.Default
    private List<Criterion> statedCriteria = new ArrayList<>();

    /** Criteria the planner inferred but the user did not state
     *  verbatim. Origin must be one of the
     *  {@code INFERRED_*}-family. Each carries a
     *  {@link Criterion#getRationaleId()} pointing into
     *  {@link ArchitectState#getRationales()}.
     *
     *  <p>The CONFIRMING phase partitions these by
     *  {@link Criterion#getConfidence()}: above the recipe's
     *  threshold → audit-pass-through; below → inbox question
     *  for explicit user confirmation. Confirmed entries get
     *  their {@link Criterion#getOrigin()} flipped to
     *  {@link CriterionOrigin#USER_CONFIRMED}. */
    @Builder.Default
    private List<Criterion> assumedCriteria = new ArrayList<>();
}
