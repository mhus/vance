package de.mhus.vance.api.slartibartfast;

/**
 * How firm a {@link Claim} is — assigned by the CLASSIFYING phase
 * and read by the BINDING validator. The validator rejects subgoals
 * whose only support is {@link #OPINION} or {@link #OUTDATED}: in
 * that case the planner must either (a) find a {@link #FACT}-tier
 * citation or (b) explicitly mark the subgoal speculative with a
 * rationale.
 */
public enum ClassificationKind {
    /** Statement that holds without context — a measurable
     *  fact, a defined term, a hard constraint. */
    FACT,

    /** Concrete worked example that illustrates a fact. Weaker
     *  than {@link #FACT} (it shows what's possible, not what's
     *  required) but stronger than {@link #OPINION}. */
    EXAMPLE,

    /** Subjective preference, recommendation, or stylistic
     *  guidance. May be cited only as one of multiple supports
     *  for a non-speculative subgoal. */
    OPINION,

    /** Statement marked as superseded or no longer applicable
     *  (e.g. references an old API). Validator ignores it for
     *  evidence purposes. */
    OUTDATED,
}
