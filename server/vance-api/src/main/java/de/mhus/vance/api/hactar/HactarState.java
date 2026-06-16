package de.mhus.vance.api.hactar;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mutable runtime state of a Hactar process (v2 — executor-only).
 * Persisted on {@code ThinkProcessDocument.engineParams.deepThoughtState};
 * restored verbatim on resume so a Brain restart picks up at the
 * current phase.
 *
 * <p>Hactar v2 is a pure script-execution engine — there are no
 * LLM-authoring phases (those moved to {@code SlartibartfastEngine}
 * with {@code OutputSchemaType.SCRIPT_JS}). Fields here cover the
 * load + (optional) deep-validate + execute pipeline.
 *
 * <p>See {@code planning/script-architect-executor-split.md} §5.2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HactarState {

    /** Document path the script was loaded from. Set by LOADING
     *  for audit/log purposes; mirrors the {@code scriptRef}
     *  engine-param. */
    private @Nullable String scriptRef;

    /** Script language — currently only {@code "js"} is supported.
     *  Reserved for future expansion (Python via separate spec). */
    @Builder.Default
    private String language = "js";

    /** Verbatim script body loaded by LOADING. EXECUTING reads
     *  this when calling {@code ScriptExecutor.run(...)}. */
    private @Nullable String scriptBody;

    /** Opt-in flag — when {@code true} the VALIDATING phase runs
     *  before EXECUTING, calling
     *  {@code HactarService.deepValidate(...)} for a semantic LLM
     *  review. Default {@code false} — cheap pipelines (scheduler-
     *  driven cron runs, Slart-self-execute) skip the LLM cost. */
    private boolean validateBeforeRun;

    /** Issues recorded by LOADING ({@code HactarService.validate})
     *  or VALIDATING ({@code HactarService.deepValidate}). Empty
     *  on the happy path. Persisted so the parent process /
     *  Cortex run-panel can surface them on FAILED status. */
    @Builder.Default
    private List<ValidationIssue> validationIssues = new ArrayList<>();

    /** Script return value (only set on a successful EXECUTING pass).
     *  JSON-friendly Java object — primitives stay primitives, JS
     *  objects become {@link java.util.Map}, JS arrays become
     *  {@link java.util.List}. {@code null} when the script returned
     *  nothing or when EXECUTING wasn't reached. */
    private @Nullable Object executionResult;

    /** Stack-trace-style error from a failed EXECUTING pass —
     *  captured from {@code ScriptExecutionException.getMessage()}.
     *  {@code null} on success or before EXECUTING. */
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

    @Builder.Default
    private HactarStatus status = HactarStatus.READY;

    /** Set when {@link #status} = {@link HactarStatus#FAILED}. */
    private @Nullable String failureReason;

    /**
     * One validation issue — mirrors
     * {@code HactarService.ValidationIssue} but lives in the API
     * module so it can flow over the wire (e.g. into
     * {@code summarizeForParent} or the Cortex result endpoint).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationIssue {
        /** {@code ERROR} | {@code WARN} | {@code INFO}. */
        private @Nullable String severity;
        /** Short keyword: {@code syntax}, {@code invalid_header},
         *  {@code missing_required_tool}, {@code logic}, ... */
        private @Nullable String code;
        private @Nullable String message;
        /** 1-based, {@code null} when not pinned. */
        private @Nullable Integer line;
        private @Nullable Integer column;
    }
}
