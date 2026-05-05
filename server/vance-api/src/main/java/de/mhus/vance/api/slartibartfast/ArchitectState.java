package de.mhus.vance.api.slartibartfast;

import java.util.ArrayList;
import java.util.List;
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

    @Builder.Default
    private OutputSchemaType outputSchemaType = OutputSchemaType.VOGON_STRATEGY;

    @Builder.Default
    private ArchitectStatus status = ArchitectStatus.READY;

    /** Set after FRAMING. */
    private @Nullable FramedGoal goal;

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
    private int maxRecoveries = 5;

    /** Lower bound for {@link Criterion#getConfidence()} that
     *  bypasses CONFIRMING. Inferred criteria with confidence
     *  &gt;= this threshold are taken for granted (with audit);
     *  below threshold they go to the user via inbox. */
    @Builder.Default
    private double confirmationThreshold = 0.85;

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

    /** Inbox-item id created when {@link #status} is
     *  {@link ArchitectStatus#CONFIRMING} (low-confidence
     *  inferred criteria) or {@link ArchitectStatus#ESCALATED}
     *  (validation gave up). The user's answer triggers
     *  re-entry. */
    private @Nullable String escalationInboxItemId;
}
