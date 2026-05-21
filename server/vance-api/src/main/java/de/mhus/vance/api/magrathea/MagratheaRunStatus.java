package de.mhus.vance.api.magrathea;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Overall status of a workflow run ({@code MagratheaProcess}). Reconstructed
 * by reading the most recent {@code StatusRecord} from the journal
 * (plan §3.2, analog Nimbus' {@code WorkflowContext.getStatus()}).
 */
@GenerateTypeScript("magrathea")
public enum MagratheaRunStatus {
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
