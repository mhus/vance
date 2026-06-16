package de.mhus.vance.api.hactar;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Phase the Hactar engine is in for a given process. Hactar v2 is a
 * pure script-execution engine (no LLM-authoring) — the lifecycle is
 * the minimal {@code READY → LOADING → [VALIDATING] → EXECUTING →
 * DONE} pipeline. Script authoring lives in
 * {@code SlartibartfastEngine} with {@code OutputSchemaType.SCRIPT_JS}.
 *
 * <p>See {@code planning/script-architect-executor-split.md}.
 */
@GenerateTypeScript("hactar")
public enum HactarStatus {
    /** Just created — runTurn transitions directly to LOADING. */
    READY,
    /** Load + minimal validation. Reads the script body from the
     *  document at {@code engineParams.scriptRef} via the document
     *  cascade, parses the JSDoc header, runs
     *  {@code HactarService.validate(...)} (parse + header + tool-
     *  allowlist intersect). On any minimal-validation failure →
     *  FAILED. Otherwise transitions to VALIDATING (when
     *  {@code validateBeforeRun=true}) or directly to EXECUTING. */
    LOADING,
    /** Opt-in deep semantic validation via
     *  {@code HactarService.deepValidate(...)} (LightLlm review).
     *  Only entered when {@code engineParams.validateBeforeRun=true}.
     *  On {@code ValidationResult.ok=false} → FAILED with the
     *  issues in the failure reason. Otherwise → EXECUTING. */
    VALIDATING,
    /** Runs the validated script via {@code ScriptExecutor}.
     *  Long-running scripts emit heartbeats through the host-API
     *  {@code vance.process.progress(message, payload)}. */
    EXECUTING,
    /** Final-state happy path. */
    DONE,
    /** Final-state error path — minimal-validation failure,
     *  deep-validate reject, executor exception, or script throw. */
    FAILED
}
