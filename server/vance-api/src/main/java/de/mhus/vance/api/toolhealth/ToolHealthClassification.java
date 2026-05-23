package de.mhus.vance.api.toolhealth;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Outcome of a tool-error analysis. Produced either by the synchronous
 * {@code FookChecker} (via pattern-matching against the error-patterns
 * cascade) or by the asynchronous Fook engine after probing.
 *
 * <p>Drives the health-document write decision — see
 * {@code specification/tool-availability.md} §3.2.
 */
@GenerateTypeScript("toolhealth")
public enum ToolHealthClassification {
    /** Tool is generally down — all users/sessions affected. Writes {@code status=DOWN}. */
    TECHNICALLY_BROKEN,
    /** Technical problem specific to one user (e.g. expired token). Writes USER-scope DOWN. */
    USER_SPECIFIC_TECHNICAL,
    /** User lacks the permission. Cooldown only — no health-doc status change. */
    USER_PERMISSION,
    /** Input didn't match the tool's contract. Short cooldown — no health-doc status change. */
    USER_INPUT,
    /** Sporadic — writes {@code status=DEGRADED} with short {@code expectedRecoveryAt}. */
    INTERMITTENT,
    /** Tool works again — clears any prior DOWN/DEGRADED. */
    WORKING,
    /** Checker cannot decide — should escalate to Fook's diagnostic engine. */
    UNCLEAR
}
