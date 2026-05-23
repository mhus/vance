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
     *  (inferred conventions/domain/context, with rationales).
     *  Also emits {@link ArchitectState#getMode()} (CREATE/EDIT),
     *  optional {@link ArchitectState#getTargetRecipeName()} and
     *  {@link ArchitectState#getRecipeName()}. */
    FRAMING,

    /** Deterministic phase (no LLM): only entered when
     *  {@link ArchitectState#getMode()} == {@link ArchitectMode#EDIT}.
     *  Loads {@code recipes/_user/<targetRecipeName>.yaml} via
     *  DocumentService, parses it, infers the
     *  {@link OutputSchemaType} from the recipe's {@code engine:}
     *  field (overriding any engineParam-supplied value), and
     *  stashes the raw + parsed yaml on the state for subsequent
     *  phases to consume. Skipped entirely when mode=CREATE. */
    LOADING_EXISTING,

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

    /** Recipe accepted — write to one of:
     *  <ul>
     *    <li>{@code recipes/_user/<recipeName>.yaml} when
     *        {@link ArchitectState#getRecipeName()} is set (named
     *        CREATE) or {@link ArchitectState#getMode()} = EDIT
     *        (overwrite the existing recipe)</li>
     *    <li>{@code recipes/_slart/<runId>/<name>.yaml} otherwise
     *        (anonymous CREATE — the legacy sandbox path).</li>
     *  </ul>
     *  Audit chain lands beside the recipe — for EDIT it also
     *  carries {@code previousRecipeYaml} for rollback. */
    PERSISTING,

    /** LLM call: decide whether to execute the freshly persisted
     *  recipe, and with what prompt. Output is one of three
     *  {@link ExecutionDecision} values. Skipped entirely when
     *  {@code engineParams.planOnly=true}. */
    EXECUTION_PLANNING,

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

    /** After PERSISTING, Slart spawns a child process from the
     *  freshly persisted recipe and parks here until the child
     *  reaches a terminal status. The child's
     *  {@code ProcessEvent} arrives via {@code drainPending};
     *  the engine then transitions to
     *  {@link #EXECUTION_VALIDATING} (child DONE) or
     *  {@link #FAILED} (child STOPPED / FAILED). Skipped when
     *  {@code engineParams.planOnly=true} — the engine goes
     *  PERSISTING → DONE directly. */
    EXECUTING,

    /** Post-execution structural validation: after the child
     *  process closes DONE, check that the artifacts the
     *  generated recipe was supposed to produce actually exist
     *  and meet minimum content thresholds. Reads expected
     *  paths from {@link Subgoal#getGoal()} via regex match.
     *  Pass → transition to {@link #DONE}. Fail → set
     *  {@link ArchitectState#getPendingRecovery()} routing back
     *  to {@link #PROPOSING} with a hint listing missing
     *  artifacts; recovery budget applies. Skipped when
     *  {@code engineParams.planOnly=true}. */
    EXECUTION_VALIDATING,

    /** Recipe persisted, DONE event emitted with the recipe path
     *  and run ID. */
    DONE,

    /** Unrecoverable error (LLM provider down, manual unreadable,
     *  …). {@link ArchitectState#getFailureReason()} explains. */
    FAILED,
}
