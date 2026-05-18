package de.mhus.vance.api.deepthought;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Phase the Deep Thought engine is in for a given process. Minimal
 * v1 set — see {@code planning/deepthought-engine.md} for the full
 * lifecycle (FRAMING/GATHERING/DECOMPOSING/REVIEWING) that will land
 * in v1.1.
 *
 * <p>The accepted script lives in {@code DeepThoughtState.generatedCode}
 * (and thus in {@code engineParams.deepThoughtState} on the
 * process document) — no separate persistence phase. Parents read
 * it back from {@code summarizeForParent} as a code block; Script
 * Cortex (v1.1) will read it directly from the engine state.
 */
@GenerateTypeScript("deepthought")
public enum DeepThoughtStatus {
    /** Just created — runTurn will transition to DRAFTING. */
    READY,
    /** LLM-call to generate the JavaScript body from the goal +
     *  any recovery hint from a prior VALIDATING failure. */
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
