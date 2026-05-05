package de.mhus.vance.api.slartibartfast;

/**
 * How CONFIRMING handles {@link FramedGoal#getAssumedCriteria()}
 * with confidence below
 * {@link ArchitectState#getConfirmationThreshold()}. Engine-param
 * steerable so different recipes can pick different policies —
 * e.g. an interactive Slartibartfast spawn might prefer
 * {@link #ASK_LOW_CONF}, while a CI-driven generation prefers
 * {@link #DROP_LOW_CONF} to stay headless.
 *
 * <p>{@link CriterionOrigin#USER_CONFIRMED} criteria pass through
 * unconditionally regardless of mode — the user already said yes.
 */
public enum ConfirmationMode {
    /**
     * Default. Low-confidence assumed criteria are dropped from
     * the working set; an informational
     * {@link ValidationCheck} entry records each drop. M3.1
     * behaviour.
     */
    DROP_LOW_CONF,

    /**
     * Permissive. Every assumed criterion passes through into
     * {@link ArchitectState#getAcceptanceCriteria()} regardless
     * of confidence. Useful when the planner wants to err on the
     * side of including everything; downstream BINDING /
     * VALIDATING still constrain the plan.
     */
    KEEP_ALL,

    /**
     * Interactive. Low-confidence assumed criteria are surfaced
     * to the user via an inbox item; the engine parks until the
     * answer arrives. The user's response flips each accepted
     * criterion's {@link Criterion#getOrigin()} to
     * {@link CriterionOrigin#USER_CONFIRMED} (passes through);
     * rejected ones are dropped with audit. Requires the inbox
     * subsystem — implemented in M6.2.
     */
    ASK_LOW_CONF,
}
