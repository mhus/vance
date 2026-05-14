package de.mhus.vance.api.hactar;

/**
 * Overall status of a workflow run ({@code HactarProcess}). Reconstructed
 * by reading the most recent {@code StatusRecord} from the journal
 * (plan §3.2, analog Nimbus' {@code WorkflowContext.getStatus()}).
 */
public enum HactarRunStatus {
    /** Active — tasks are queued, claimed or waiting. */
    RUNNING,
    /** Terminal — terminal state reached with success outcome. */
    DONE,
    /** Terminal — terminal state reached with failure outcome. */
    FAILED,
    /** Terminal — explicitly cancelled by user or pod-shutdown without recovery. */
    TERMINATED,
    /** Inactive — bounds exhausted or explicit {@code pause} action; awaiting user intervention. */
    PAUSED
}
