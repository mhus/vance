package de.mhus.vance.api.slartibartfast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One acceptance criterion attached to a {@link FramedGoal}.
 * Criteria are testable predicates — the VALIDATING phase checks
 * that every criterion is addressed by at least one
 * {@link Subgoal}.
 *
 * <p>{@link #getOrigin()} distinguishes criteria the user stated
 * verbatim from those the planner inferred. The CONFIRMING phase
 * uses the combination of {@code origin} + {@link #getConfidence()}
 * to decide whether to request inbox confirmation:
 * {@link CriterionOrigin#USER_STATED} bypasses, high-confidence
 * inferred passes through with audit, low-confidence inferred
 * lands in an inbox question.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Criterion {

    /** Stable id, referenced from {@link Subgoal#getCriterionRefs()}.
     *  Conventional shape: {@code "cr1"}, {@code "cr2"}, … */
    private String id = "";

    /** Human-readable predicate, e.g. "essay/final-essay.md
     *  exists and contains at least 3 chapters". */
    private String text = "";

    /** Optional hint how the criterion can be machine-checked
     *  (path glob, doc-content regex, …). {@code null} when the
     *  criterion is verified only by the user. */
    private @Nullable String testHint;

    /** Where the criterion came from. Drives CONFIRMING and
     *  affects the strictness of the coverage check in
     *  VALIDATING. */
    @Builder.Default
    private CriterionOrigin origin = CriterionOrigin.USER_STATED;

    /** 0.0..1.0. {@link CriterionOrigin#USER_STATED} and
     *  {@link CriterionOrigin#USER_CONFIRMED} carry implicit
     *  confidence 1.0; for {@code INFERRED_*} the planner sets
     *  this based on how unambiguous the inference is.
     *  CONFIRMING compares against
     *  {@link ArchitectState#getConfirmationThreshold()}. */
    @Builder.Default
    private double confidence = 1.0;

    /** Foreign key into {@link ArchitectState#getRationales()}.
     *  Required for every {@code INFERRED_*}-origin criterion;
     *  optional (typically {@code null}) for {@link CriterionOrigin#USER_STATED}. */
    private @Nullable String rationaleId;
}
