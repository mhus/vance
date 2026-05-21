package de.mhus.vance.api.hactar;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mutable runtime state of a Hactar process. Persisted on
 * {@code ThinkProcessDocument.engineParams.deepThoughtState}; restored
 * verbatim on resume so a Brain restart picks up at the current phase
 * without losing prior LLM-drafts or validation errors.
 *
 * <p>v1 carries only the fields needed for the three-phase pipeline
 * (DRAFTING → VALIDATING → DONE, with optional EXECUTING). The
 * accepted script lives in {@link #generatedCode} — there is no
 * separate persistence to a project document, the engineParams blob
 * itself is the audit trail. Multi-script-suite, gathering,
 * decomposing land in v1.1 as additional fields.
 *
 * <p>See {@code planning/hactar-engine.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HactarState {

    /** What the user/parent asked for, copy of the process goal at
     *  start time so prompt-building stays deterministic across
     *  steers that might mutate the live goal field. Optional when
     *  {@link #scriptPath} is set (load-mode bypasses generation). */
    private @Nullable String goal;

    /** Project document path the script will be loaded from instead
     *  of being generated. When set, the engine takes the LOADING →
     *  VALIDATING → (EXECUTING) → DONE pathway and ignores
     *  FRAMING/DRAFTING. {@code null} → normal generation pathway. */
    private @Nullable String scriptPath;

    /** Raw LLM output of the most recent DRAFTING pass — fences
     *  stripped, ready to feed into VALIDATING. Surfaces verbatim in
     *  {@code summarizeForParent} so the parent sees the final
     *  script. */
    private @Nullable String generatedCode;

    /** Errors from the most recent VALIDATING pass. Populated even on
     *  success (empty list) so the UI can render the "validated, 0
     *  errors" badge. */
    @Builder.Default
    private List<ValidationError> validationErrors = new ArrayList<>();

    /** Plan-mode opt-in. When true, READY transitions to FRAMING
     *  (LLM sketch) → REVIEWING (sub-recipe worker) → DRAFTING. When
     *  false (default), READY goes straight to DRAFTING — existing
     *  one-shot behaviour. Copied from {@code engineParams.framingEnabled}
     *  at start time so a steer can't change pipeline shape mid-run. */
    private boolean framingEnabled;

    /** Plan sketch the FRAMING phase produced — Markdown describing
     *  the script's approach, sub-steps, tools to call, edge cases.
     *  Fed verbatim into the DRAFTING user message as additional
     *  context. {@code null} when FRAMING wasn't run or wasn't yet
     *  reached. */
    private @Nullable String planSketch;

    /** Reviewer's verdict line from the last REVIEWING pass —
     *  {@code "APPROVED"} or {@code "REJECTED"}. {@code null} when
     *  REVIEWING wasn't run (no reviewer configured, or framing
     *  disabled). */
    private @Nullable String reviewerVerdict;

    /** Free-text notes / critique from the last REVIEWING pass.
     *  Carries the reviewer's reasoning verbatim — on REJECTED this
     *  becomes the hint fed back into the next FRAMING attempt. */
    private @Nullable String reviewerNotes;

    /** Number of completed FRAMING→REVIEWING recovery cycles.
     *  Independent of {@link #recoveryCount} (DRAFTING↔VALIDATING).
     *  Reaching {@link #maxFramingRecoveries} → FAILED. */
    private int framingRecoveryCount;

    /** Soft-cap on FRAMING recovery attempts. Default 3 — shorter
     *  than DRAFTING's recovery budget because each FRAMING cycle
     *  costs two LLM calls (drafter + reviewer), not one. */
    @Builder.Default
    private int maxFramingRecoveries = 3;

    /** If true, transition to EXECUTING after VALIDATING; otherwise
     *  jump straight to DONE. */
    private boolean executeOnDone;

    /** Script return value (only set on a successful EXECUTING pass).
     *  JSON-friendly Java object — primitives stay primitives, JS
     *  objects become {@link java.util.Map}, JS arrays become
     *  {@link java.util.List}. {@code null} when the script returned
     *  nothing or when EXECUTING wasn't reached. */
    private @Nullable Object executionResult;

    /** Stack-trace-style error from a failed EXECUTING pass — captured
     *  from {@code ScriptExecutionException.getMessage()}. {@code null}
     *  on success or before EXECUTING. */
    private @Nullable String executionError;

    /** Classification of an EXECUTING failure — one of
     *  {@code INVALID_HEADER}, {@code MISSING_CAPABILITY},
     *  {@code TIMEOUT}, {@code STATEMENT_LIMIT_EXCEEDED},
     *  {@code RUNTIME_ERROR}, ... — see
     *  {@code ScriptExecutionException.ErrorClass}. {@code null}
     *  when EXECUTING succeeded or wasn't run. */
    private @Nullable String executionErrorClass;

    /** Wall-clock duration of the EXECUTING pass in milliseconds.
     *  {@code 0} when EXECUTING wasn't run. */
    private long executionDurationMs;

    /** Number of completed DRAFTING→VALIDATING recovery cycles. Hits
     *  {@link #maxRecoveries} → FAILED. */
    private int recoveryCount;

    /** Soft-cap on recovery attempts. Default 5 — enough to recover
     *  from "forgot a brace" but not so high that a hallucinating
     *  model loops forever burning tokens. */
    @Builder.Default
    private int maxRecoveries = 5;

    @Builder.Default
    private HactarStatus status = HactarStatus.READY;

    /** Set when {@link #status} = {@link HactarStatus#FAILED}. */
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
