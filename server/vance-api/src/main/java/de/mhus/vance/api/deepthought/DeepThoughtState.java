package de.mhus.vance.api.deepthought;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mutable runtime state of a Deep Thought process. Persisted on
 * {@code ThinkProcessDocument.engineParams.deepThoughtState}; restored
 * verbatim on resume so a Brain restart picks up at the current phase
 * without losing prior LLM-drafts or validation errors.
 *
 * <p>v1 carries only the fields needed for the four-phase pipeline
 * (DRAFTING → VALIDATING → PERSISTING → DONE). Multi-script-suite,
 * gathering, decomposing land in v1.1 as additional fields.
 *
 * <p>See {@code planning/deepthought-engine.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeepThoughtState {

    /** What the user/parent asked for, copy of the process goal at
     *  start time so prompt-building stays deterministic across
     *  steers that might mutate the live goal field. */
    private @Nullable String goal;

    /** Target filename (without the {@code scripts/} prefix) the
     *  generated body will be persisted to. Resolved from
     *  {@code engineParams.targetName} at start; falls back to
     *  {@code "generated.js"} if absent. */
    private @Nullable String targetName;

    /** Raw LLM output of the most recent DRAFTING pass. Carries the
     *  full message — VALIDATING strips fences and updates this with
     *  the cleaned body before persisting. */
    private @Nullable String generatedCode;

    /** Errors from the most recent VALIDATING pass. Populated even on
     *  success (empty list) so the UI can render the "validated, 0
     *  errors" badge. */
    @Builder.Default
    private List<ValidationError> validationErrors = new ArrayList<>();

    /** Document path written by PERSISTING — null until persist
     *  succeeds. v1 always writes through doc_write_text relative to
     *  the project root. */
    private @Nullable String persistedPath;

    /** If true, transition to EXECUTING after PERSISTING; otherwise
     *  jump straight to DONE. v1 leaves this at {@code false} —
     *  EXECUTING phase is stubbed pending Script Cortex. */
    private boolean executeOnDone;

    /** Number of completed DRAFTING→VALIDATING recovery cycles. Hits
     *  {@link #maxRecoveries} → FAILED. */
    private int recoveryCount;

    /** Soft-cap on recovery attempts. Default 5 — enough to recover
     *  from "forgot a brace" but not so high that a hallucinating
     *  model loops forever burning tokens. */
    @Builder.Default
    private int maxRecoveries = 5;

    @Builder.Default
    private DeepThoughtStatus status = DeepThoughtStatus.READY;

    /** Set when {@link #status} = {@link DeepThoughtStatus#FAILED}. */
    private @Nullable String failureReason;

    /**
     * One validation error — mirrors
     * {@code JsValidationService.JsValidationError} but lives in the
     * API module so it can flow over the wire (e.g. into
     * {@code summarizeForParent}). {@code line}/{@code column} 0 when
     * the parser didn't supply a SourceSection.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        private @Nullable String sourceName;
        private int line;
        private int column;
        private @Nullable String message;
    }
}
