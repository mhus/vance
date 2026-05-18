package de.mhus.vance.api.deepthought;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Phase the Deep Thought engine is in for a given process. Minimal
 * v1 set — see {@code planning/deepthought-engine.md} for the full
 * lifecycle (FRAMING/GATHERING/DECOMPOSING/REVIEWING) that will land
 * in v1.1.
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
    /** Write the accepted script to {@code scripts/<name>.js} via
     *  doc_write_text. */
    PERSISTING,
    /** Optional — only entered when {@code executeOnDone=true}.
     *  Calls {@code process_run} / {@code execute_javascript} on the
     *  persisted script. */
    EXECUTING,
    /** Final-state happy path. */
    DONE,
    /** Final-state error path — either max-recoveries hit or a
     *  non-recoverable error from a phase. */
    FAILED
}
