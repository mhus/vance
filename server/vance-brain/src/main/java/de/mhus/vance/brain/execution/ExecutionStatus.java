package de.mhus.vance.brain.execution;

/**
 * Status union shared between brain {@code ExecJob.Status} and foot
 * {@code ClientExecJob.Status}. Brain-side has the extra {@link
 * #ORPHANED} state for jobs whose process was lost across a restart;
 * foot-side never produces it but the registry can carry it.
 */
public enum ExecutionStatus {
    RUNNING, COMPLETED, FAILED, KILLED, ORPHANED
}
