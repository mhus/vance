package de.mhus.vance.api.hactar;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Phase the Hactar engine is in for a given process. Minimal
 * v1 set — see {@code planning/hactar-engine.md} for the full
 * lifecycle (FRAMING/GATHERING/DECOMPOSING/REVIEWING) that will land
 * in v1.1.
 *
 * <p>The accepted script lives in {@code HactarState.generatedCode}
 * (and thus in {@code engineParams.deepThoughtState} on the
 * process document) — no separate persistence phase. Parents read
 * it back from {@code summarizeForParent} as a code block; Script
 * Cortex (v1.1) will read it directly from the engine state.
 */
@GenerateTypeScript("hactar")
public enum HactarStatus {
    /** Just created — runTurn transitions to LOADING (when
     *  {@code scriptPath} is set), FRAMING (when {@code framingEnabled}),
     *  or directly to DRAFTING. */
    READY,
    /** Load-mode: fetch the script from the document path given in
     *  {@code engineParams.scriptPath} and feed it straight into
     *  VALIDATING. Bypasses generation entirely. Used by Script
     *  Cortex's "validate this file" / "run this file" pathways. */
    LOADING,
    /** Plan-mode: LLM produces a structured sketch of the script's
     *  approach (sub-steps, tools to call, edge cases) before any
     *  code is drafted. Only entered when {@code framingEnabled=true}. */
    FRAMING,
    /** Plan-review: a sub-recipe worker judges the plan sketch with
     *  a VERDICT line (APPROVED / REJECTED). On REJECTED, the engine
     *  loops back to FRAMING with the reviewer's critique as hint,
     *  up to {@code maxFramingRecoveries}. Skipped when no reviewer
     *  recipe is configured or resolvable. */
    REVIEWING,
    /** LLM-call to generate the JavaScript body from the goal +
     *  (when present) the approved plan sketch + any recovery hint
     *  from a prior VALIDATING failure. */
    DRAFTING,
    /** Parse-only check via {@code JsValidationService}. On
     *  syntax error → recovery back to DRAFTING with the error
     *  line/col as the prompt-hint. */
    VALIDATING,
    /** Optional — only entered when {@code executeOnDone=true}.
     *  Calls {@code process_run} / {@code execute_javascript} on the
     *  validated script (Script Cortex integration, lands in v1.1). */
    EXECUTING,
    /** Final-state happy path. */
    DONE,
    /** Final-state error path — either max-recoveries hit or a
     *  non-recoverable error from a phase. */
    FAILED
}
