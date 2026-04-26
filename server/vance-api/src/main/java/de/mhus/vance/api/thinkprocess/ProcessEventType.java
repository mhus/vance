package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * The kind of life-cycle event a think-process emits to its parent
 * (orchestrator) process. Carried by
 * {@code SteerMessage.ProcessEvent} on the engine side and the
 * persistent {@code PendingMessageDocument} on the storage side.
 *
 * <p>Mirrors {@code ThinkProcessStatus} only loosely — events are
 * about <em>what just happened</em>, not the live status field, and
 * include {@link #SUMMARY} which has no status counterpart.
 */
@GenerateTypeScript("thinkprocess")
public enum ProcessEventType {
    /** Process has been spawned and started its first turn. */
    STARTED,
    /** Process is waiting on user input or approval. */
    BLOCKED,
    /** Process reached its goal — terminal. */
    DONE,
    /** Process failed with an error — terminal. */
    FAILED,
    /** Process was stopped by user / parent / session-close — terminal. */
    STOPPED,
    /** Mid-flight progress note pushed by the worker (no status change). */
    SUMMARY
}
