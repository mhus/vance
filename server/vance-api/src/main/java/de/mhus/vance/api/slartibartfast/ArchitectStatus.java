package de.mhus.vance.api.slartibartfast;

/**
 * Lifecycle states of a Slartibartfast process. The engine's runTurn
 * dispatches on this enum and persists the transition together with
 * the phase output on {@link ArchitectState}.
 *
 * <p>Forward-only progression except for the two re-prompt loops:
 * {@code BINDING → DECOMPOSING} (insufficient evidence) and
 * {@code VALIDATING → PROPOSING} (recipe failed validation). Each
 * loop is bounded; on max-retries the engine transitions to
 * {@link #ESCALATED}.
 */
public enum ArchitectStatus {
    /** Initial state — engine just spawned, nothing done. */
    READY,

    /** LLM call: turn the user's free-text request into a
     *  {@link FramedGoal} with two distinct lists —
     *  {@link FramedGoal#getStatedCriteria()} (verbatim from the
     *  user) and {@link FramedGoal#getAssumedCriteria()}
     *  (inferred conventions/domain/context, with rationales). */
    FRAMING,

    /** Partition {@link FramedGoal#getAssumedCriteria()} by
     *  confidence: high-confidence inferences pass through into
     *  the working acceptance set with audit; low-confidence
     *  ones go to the user via inbox for explicit confirmation
     *  before planning continues. Sub-state during inbox-wait
     *  is encoded by {@link ArchitectState#getEscalationInboxItemId()}. */
    CONFIRMING,

    /** Tool calls: list and read available manuals/documents,
     *  persist as {@link EvidenceSource} entries. */
    GATHERING,

    /** LLM call per source: tag content as
     *  {@link ClassificationKind#FACT} / {@link ClassificationKind#EXAMPLE}
     *  / {@link ClassificationKind#OPINION} / {@link ClassificationKind#OUTDATED}. */
    CLASSIFYING,

    /** LLM call: produce {@link Subgoal}s, each tied to evidence
     *  refs OR explicitly marked {@link Subgoal#isSpeculative()}. */
    DECOMPOSING,

    /** Hard validator gate — every subgoal needs evidence or a
     *  speculation rationale. Failure → re-prompt DECOMPOSING. */
    BINDING,

    /** LLM call: turn the bound subgoals into a
     *  {@link RecipeDraft} (Vogon-strategy or Marvin-recipe YAML)
     *  with per-constraint justification. */
    PROPOSING,

    /** Hard validator gate — referential integrity, schema shape,
     *  speculation bound. Failure → re-prompt PROPOSING. */
    VALIDATING,

    /** Recipe accepted — write to
     *  {@code recipes/_slart/<runId>/<name>.yaml} plus an
     *  {@code audit.json} with the full evidence chain. */
    PERSISTING,

    /** {@link EscalationMode#ASK_USER}: max recoveries hit, inbox
     *  item posted, engine parks waiting for the user's verdict.
     *  On answer the engine either resets {@code recoveryCount}
     *  and resumes from the failed phase ({@code retry}) or
     *  transitions to {@link #ESCALATED} ({@code abort}). Only
     *  reachable when the recipe explicitly opts in via
     *  {@code engineParams.escalationMode=ASK_USER}. */
    ESCALATING,

    /** Validation kept failing past the retry budget. Inbox-item
     *  posted to the user with the validation report and the last
     *  recipe draft; user decides revise vs. discard. */
    ESCALATED,

    /** Recipe persisted, DONE event emitted with the recipe path
     *  and run ID. */
    DONE,

    /** Unrecoverable error (LLM provider down, manual unreadable,
     *  …). {@link ArchitectState#getFailureReason()} explains. */
    FAILED,
}
