package de.mhus.vance.api.slartibartfast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mutable runtime state of a Slartibartfast process — persisted on
 * {@code ThinkProcessDocument.engineParams.architectState}, restored
 * verbatim on resume. Single source of truth for the audit chain
 * the planner builds: every non-trivial decision references a
 * {@link Rationale} from {@link #getRationales()}; every phase
 * appends one {@link PhaseIteration} to {@link #getIterations()};
 * every LLM round-trip lands a {@link LlmCallRecord} in
 * {@link #getLlmCallRecords()}.
 *
 * <p>Lifecycle (forward order, with arbitrary recovery jumps):
 * <ol>
 *   <li>{@link ArchitectStatus#FRAMING} populates {@link #goal}
 *       (split into stated + assumed criteria, with per-criterion
 *       rationales).</li>
 *   <li>{@link ArchitectStatus#CONFIRMING} partitions
 *       {@link FramedGoal#getAssumedCriteria()}: high-confidence
 *       pass through, low-confidence become an inbox question.</li>
 *   <li>{@link ArchitectStatus#GATHERING} populates
 *       {@link #evidenceSources} (each with a
 *       {@link EvidenceSource#getGatheringRationaleId()}).</li>
 *   <li>{@link ArchitectStatus#CLASSIFYING} populates
 *       {@link #evidenceClaims} (each non-FACT classification
 *       carries a {@link Claim#getClassificationRationaleId()}).</li>
 *   <li>{@link ArchitectStatus#DECOMPOSING} populates
 *       {@link #subgoals} and {@link #decompositionRationaleId}.</li>
 *   <li>{@link ArchitectStatus#BINDING} runs validators; failures
 *       set {@link #pendingRecovery} and route control back.</li>
 *   <li>{@link ArchitectStatus#PROPOSING} populates
 *       {@link #proposedRecipe} (with its
 *       {@link RecipeDraft#getShapeRationaleId()} and per-constraint
 *       {@link RecipeDraft#getJustifications()}).</li>
 *   <li>{@link ArchitectStatus#VALIDATING} runs validators; failures
 *       set {@link #pendingRecovery} (target may be any earlier
 *       phase, not just PROPOSING).</li>
 *   <li>{@link ArchitectStatus#PERSISTING} writes the recipe and
 *       fills {@link #terminationRationale} for the DONE-payload.</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchitectState {

    /** First 8 hex chars of a UUIDv4. Determines the storage
     *  bucket {@code recipes/_slart/<runId>/}. Generated once at
     *  spawn and never mutated. */
    private String runId = "";

    /** Verbatim user request — convenience copy. Same string ends
     *  up in {@link FramedGoal#getSourceUserText()} after
     *  FRAMING. */
    private String userDescription = "";

    /**
     * Optional free-text guidance the caller (recipe / kit / SKILL via
     * Arthur) supplies for Slart's PROPOSING phase. Appended verbatim
     * to the PROPOSING system-prompt under a clearly labelled "Kit-
     * provided guidance" header. Use for kit-specific recipe-shape
     * conventions that aren't encoded in the framed goal or
     * acceptance criteria — typical example: "persist artifacts via
     * doc_create" patterns from a writing-kit. Slart's generic
     * engine prompt stays untouched; the kit owns its own additions.
     *
     * <p>Empty / null = no guidance, default behaviour.
     */
    private @Nullable String proposingHints;

    @Builder.Default
    private OutputSchemaType outputSchemaType = OutputSchemaType.VOGON_STRATEGY;

    /** CREATE or EDIT — emitted by FRAMING, drives the
     *  LOADING_EXISTING phase, the PROPOSING "invent vs. patch"
     *  switch, and the PERSISTING write-path. See
     *  {@link ArchitectMode}. */
    @Builder.Default
    private ArchitectMode mode = ArchitectMode.CREATE;

    /** Set by FRAMING for CREATE when the user description includes
     *  "speicher es unter 'X'" / "save as X" / "name it X".
     *  Drives PERSISTING to write to {@code recipes/_user/<recipeName>.yaml}
     *  instead of the legacy {@code _slart/<runId>/} sandbox.
     *  Null = anonymous run, sandbox path. */
    private @Nullable String recipeName;

    /** EDIT only — name of the existing recipe to patch. Set by
     *  FRAMING from "Erweitere 'X'", "modifiziere 'X'", "ändere
     *  X" patterns. LOADING_EXISTING loads
     *  {@code recipes/_user/<targetRecipeName>.yaml} and stashes
     *  the parsed result in {@link #existingRecipeYaml} +
     *  {@link #existingRecipeMap}. */
    private @Nullable String targetRecipeName;

    /** EDIT only — natural-language description of what to
     *  change in the target recipe. Reaches PROPOSING as part
     *  of the user prompt so the LLM knows what to patch and
     *  what to leave intact. */
    private @Nullable String modificationSummary;

    /** EDIT only — raw YAML of the loaded existing recipe.
     *  PROPOSING gets this as a verbatim reference so it can
     *  "patch instead of invent". */
    private @Nullable String existingRecipeYaml;

    /** EDIT only — parsed top-level map of the existing recipe.
     *  Useful for downstream inspection (justifications can
     *  reference unchanged constraint-keys symbolically). */
    private @Nullable Map<String, Object> existingRecipeMap;

    /** UPDATE only — document path to the existing artefact that
     *  this run modifies. For SCRIPT_JS UPDATE this is the script
     *  document the caller (Cortex, an inbox action, …) wants
     *  enhanced. Read by LOADING_EXISTING; PROPOSING gets the
     *  loaded body in {@link #existingScriptCode}. Null in
     *  CREATE/EDIT runs. */
    private @Nullable String existingScriptRef;

    /** UPDATE only — verbatim body of the artefact loaded from
     *  {@link #existingScriptRef}. The architect's
     *  {@code appendProposingContext} hands this to the LLM as
     *  the "existing code" reference block. Null in CREATE/EDIT
     *  runs. */
    private @Nullable String existingScriptCode;

    /** UPDATE only — optional failure reason from a prior Hactar
     *  run (e.g. {@code TerminationRationale.failureReason} after
     *  a FAILED execution). When set, the architect's
     *  {@code appendProposingContext} adds it to the prompt so
     *  the LLM knows what the previous attempt got wrong. Null
     *  for plain feature-add updates.
     *
     *  <p>Disambiguated from {@link #failureReason} (this run's
     *  own failure reason when {@link #status} is FAILED) by the
     *  {@code prior} prefix — the engine-param key on the public
     *  surface is just {@code failureReason} (planning §5.1) and
     *  {@code SlartibartfastEngine.buildInitialState} maps it
     *  into this field. */
    private @Nullable String priorFailureReason;

    @Builder.Default
    private ArchitectStatus status = ArchitectStatus.READY;

    /** Set after FRAMING. */
    private @Nullable FramedGoal goal;

    /** Output of CONFIRMING — the unified working set of criteria
     *  the rest of the plan must satisfy. Always contains every
     *  {@link FramedGoal#getStatedCriteria()} entry; for assumed
     *  criteria the entry is included iff its
     *  {@link Criterion#getConfidence()} is at least
     *  {@link #getConfirmationThreshold()} (high-confidence
     *  pass-through) or its {@link Criterion#getOrigin()} flipped
     *  to {@link CriterionOrigin#USER_CONFIRMED} via inbox. Low-
     *  confidence assumed entries that the user did not confirm
     *  are absent from this list and recorded in
     *  {@link #validationReport} as informational drops. */
    @Builder.Default
    private List<Criterion> acceptanceCriteria = new ArrayList<>();

    /** Populated incrementally during GATHERING. */
    @Builder.Default
    private List<EvidenceSource> evidenceSources = new ArrayList<>();

    /** Populated by CLASSIFYING — one pass per source. */
    @Builder.Default
    private List<Claim> evidenceClaims = new ArrayList<>();

    /** Output of DECOMPOSING. Replaced wholesale on each
     *  re-prompt triggered by a {@link RecoveryRequest} that
     *  routed to DECOMPOSING. */
    @Builder.Default
    private List<Subgoal> subgoals = new ArrayList<>();

    /** Foreign key into {@link #rationales} — meta-rationale for
     *  the <em>set</em> of subgoals (why this decomposition
     *  shape, why N steps, why this ordering). Distinct from the
     *  per-subgoal evidence chain. {@code null} until DECOMPOSING
     *  has run at least once. */
    private @Nullable String decompositionRationaleId;

    /** Output of PROPOSING. Replaced wholesale on each re-prompt. */
    private @Nullable RecipeDraft proposedRecipe;

    /** Append-only pool of {@link Rationale} entries referenced
     *  by other artifacts. Survives recovery rollbacks — old
     *  rationales stay even when their referencing artifact is
     *  replaced, so the audit log is reconstructable. */
    @Builder.Default
    private List<Rationale> rationales = new ArrayList<>();

    /** Append-only history of every phase pass. Each
     *  {@link PhaseIteration} captures inputs, outputs, outcome,
     *  and a {@link PhaseIteration#getLlmCallRecordId()} link to
     *  the LLM call (if any). The recovery cycle is
     *  reconstructable by replaying entries in order. */
    @Builder.Default
    private List<PhaseIteration> iterations = new ArrayList<>();

    /** Append-only audit of LLM round-trips. Capture is governed
     *  by {@link #auditLlmCalls}. */
    @Builder.Default
    private List<LlmCallRecord> llmCallRecords = new ArrayList<>();

    /** Latest phase-tier {@link ValidationCheck} results. Cleared
     *  when a re-prompt is about to run so only the current
     *  iteration's checks show in the report. Permanent history
     *  lives in {@link #iterations}. */
    @Builder.Default
    private List<ValidationCheck> validationReport = new ArrayList<>();

    /** Set by a validator that wants control rolled back to an
     *  earlier phase. Engine consumes it at the top of the next
     *  {@code runTurn}: status flips to
     *  {@link RecoveryRequest#getToPhase()} and the field is
     *  cleared. {@code null} during forward progression. */
    private @Nullable RecoveryRequest pendingRecovery;

    /** Counts every {@link RecoveryRequest} consumed during the
     *  run. When it reaches {@link #maxRecoveries} the engine
     *  transitions to {@link ArchitectStatus#ESCALATED} with the
     *  last failed validator output as the inbox-discussion
     *  context. */
    private int recoveryCount;

    /** Replaces the per-pair retry counts. Counts <em>any</em>
     *  recovery rollback regardless of source/target phase. */
    @Builder.Default
    private int maxRecoveries = 10;

    /** Lower bound for {@link Criterion#getConfidence()} that
     *  bypasses CONFIRMING. Inferred criteria with confidence
     *  &gt;= this threshold are taken for granted (with audit);
     *  below threshold the {@link #confirmationMode} decides
     *  what happens. */
    @Builder.Default
    private double confirmationThreshold = 0.85;

    /** How CONFIRMING handles low-confidence assumed criteria.
     *  Engine-param steerable via the recipe / spawn-time
     *  {@code engineParams.confirmationMode}. Default
     *  {@link ConfirmationMode#DROP_LOW_CONF} preserves the M3.1
     *  silent-drop behaviour. */
    @Builder.Default
    private ConfirmationMode confirmationMode = ConfirmationMode.DROP_LOW_CONF;

    /** How the engine handles recovery-budget exhaustion.
     *  Engine-param steerable via
     *  {@code engineParams.escalationMode}. Default
     *  {@link EscalationMode#FAIL} preserves the M4.1 ESCALATED-
     *  close behaviour. */
    @Builder.Default
    private EscalationMode escalationMode = EscalationMode.FAIL;

    /** Maximum fraction of {@link #subgoals} that may be
     *  {@code speculative}. The VALIDATING speculation-bound check
     *  rejects the recipe when exceeded. */
    @Builder.Default
    private double maxSpeculativeRatio = 0.30;

    /** When {@code true}, every LLM call appends to
     *  {@link #llmCallRecords}. Default {@code true} for
     *  Slartibartfast — the audit cost is small, the
     *  reproducibility benefit is large. */
    @Builder.Default
    private boolean auditLlmCalls = true;

    /** Set by PERSISTING to summarise convergence. Exported on
     *  the DONE-payload + persisted as {@code audit.json}
     *  alongside the recipe. {@code null} until DONE. */
    private @Nullable TerminationRationale terminationRationale;

    /** Document path after PERSISTING — e.g.
     *  {@code "recipes/_slart/3a4f7c91/essay-pipeline.yaml"}.
     *  {@code null} until DONE. */
    private @Nullable String persistedRecipePath;

    /** Set when {@link #status} is {@link ArchitectStatus#FAILED}. */
    private @Nullable String failureReason;

    /** Inbox-item id of an outstanding dialog. Set when CONFIRMING
     *  (mode=ASK_LOW_CONF) or the engine's escalation handler
     *  (mode=ASK_USER) post an inbox item; cleared by
     *  {@code SlartibartfastEngine.runTurnInner}'s
     *  drain-pending-handler once the user's
     *  {@link de.mhus.vance.api.inbox.AnswerPayload} arrives. */
    private @Nullable String pendingInboxItemId;

    /** Discriminator that tells the engine how to interpret a
     *  pending inbox answer. {@link PendingInboxKind#NONE} when
     *  no dialog is in flight. */
    @Builder.Default
    private PendingInboxKind pendingInboxKind = PendingInboxKind.NONE;

    /** @deprecated Pre-M6.2 alias for {@link #pendingInboxItemId}.
     *  Kept for back-compat with audit dumps that still reference
     *  the old name. New code should use
     *  {@link #pendingInboxItemId}. */
    @Deprecated
    private @Nullable String escalationInboxItemId;

    /** When {@code true}, Slart stops after PERSISTING with status
     *  {@link ArchitectStatus#DONE} — it produced a recipe and an
     *  audit chain, that's it. When {@code false} (default), it
     *  also spawns a child process from the persisted recipe,
     *  parks in {@link ArchitectStatus#EXECUTING} until the child
     *  closes, then runs {@link ArchitectStatus#EXECUTION_VALIDATING}
     *  before flipping to DONE.
     *
     *  <p>Default is execute-and-validate because that's what most
     *  callers want from a "schreib mir X" instruction. Set
     *  {@code engineParams.planOnly=true} for plan-only mode
     *  (cheap, deterministic — useful for debugging, recipe
     *  export, version-control of plans, audit). */
    @Builder.Default
    private boolean planOnly = false;

    /** Set when EXECUTING spawned the child process. Lets the
     *  engine recognise the matching {@code ProcessEvent} on
     *  {@code drainPending} and survive a restart with the child
     *  reference intact. */
    private @Nullable String childExecutionProcessId;

    /** Records the child's terminal close-reason name (e.g.
     *  {@code "DONE"}, {@code "STALE"}, {@code "STOPPED"}) after
     *  EXECUTING saw the matching {@code ProcessEvent}. {@code
     *  null} until then. */
    private @Nullable String childExecutionOutcome;

    /** Human-readable summary of the child's terminal event —
     *  comes from {@code ProcessEvent.humanSummary()}. Surfaces
     *  on Slart's own DONE/FAILED payload so callers can see
     *  what happened without resolving the child process
     *  document. */
    private @Nullable String childExecutionSummary;

    /** EXECUTION_PLANNING output — verdict on whether/how to
     *  run the freshly persisted recipe. {@code null} until
     *  EXECUTION_PLANNING runs (or planOnly=true skipped it). */
    private @Nullable ExecutionDecision executionDecision;

    /** EXECUTION_PLANNING output — the prompt that will be used
     *  as the goal for the spawned child process. Equal to the
     *  user description on USE_USER_PROMPT, or a Slart-LLM-
     *  generated test prompt on USE_GENERATED_PROMPT. {@code null}
     *  on SKIP. */
    private @Nullable String executionPrompt;

    /** EXECUTION_PLANNING output — natural-language rationale
     *  for the decision. Surfaces on DONE-payload so the caller
     *  (Arthur) can explain Slart's behavior. */
    private @Nullable String executionDecisionReason;
}
