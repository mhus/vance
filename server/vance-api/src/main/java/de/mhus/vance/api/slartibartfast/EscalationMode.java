package de.mhus.vance.api.slartibartfast;

/**
 * How the engine handles exhaustion of the recovery budget — i.e.
 * a downstream gate keeps requesting rollbacks until
 * {@link ArchitectState#getRecoveryCount()} hits
 * {@link ArchitectState#getMaxRecoveries()}. Engine-param
 * steerable.
 *
 * <p>Distinct from individual phase failures: a phase that fails
 * outright (LLM provider down, malformed JSON past the per-phase
 * retry budget) flips to {@link ArchitectStatus#FAILED}
 * regardless of this mode. EscalationMode only governs the
 * "validators kept rejecting it" case.
 */
public enum EscalationMode {
    /**
     * Default. Run ends in {@link ArchitectStatus#ESCALATED}; the
     * caller (typically Arthur or a test) inspects
     * {@link ArchitectState#getValidationReport()} to decide what
     * to do.
     */
    FAIL,

    /**
     * Interactive. The engine posts an inbox item with the last
     * {@link ValidationCheck} report and the latest
     * {@link RecipeDraft}, then parks. The user's answer either
     * grants a fresh recovery budget (engine resumes from the
     * last failing phase) or accepts the failure. Requires the
     * inbox subsystem — implemented in M6.2.
     */
    ASK_USER,
}
