package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * The kind of life-cycle event a think-process emits — usually to its
 * parent (orchestrator) process, occasionally to itself (timer-driven
 * self-wakeups). Carried by {@code SteerMessage.ProcessEvent} on the
 * engine side and the persistent {@code PendingMessageDocument} on the
 * storage side.
 *
 * <p>Mirrors {@code ThinkProcessStatus} only loosely — events are
 * about <em>what just happened</em>, not the live status field, and
 * include {@link #SUMMARY} and {@link #SCHEDULED_WAKEUP}, neither of
 * which has a status counterpart.
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
    SUMMARY,
    /**
     * A previously-scheduled self-wakeup timer fired. The event is
     * self-addressed ({@code sourceProcessId == targetProcessId}) and
     * carries the correlationId + label + caller-supplied payload from
     * the original {@code wakeup_in} call. Engines that don't have
     * specific handling can treat it as a generic resume-turn trigger.
     */
    SCHEDULED_WAKEUP,
    /**
     * A previously-started exec job reached its natural terminal state
     * (subprocess exited, status COMPLETED/FAILED, or {@code exec_kill}
     * was used). The event is self-addressed to the process that
     * spawned the job and carries {@code jobId}, {@code exitCode},
     * {@code status}, and a short output tail. Watchdog-driven kills
     * use {@link #EXEC_TIMEOUT} instead so engines can distinguish
     * "job finished" from "we gave up on it".
     */
    EXEC_FINISHED,
    /**
     * The exec watchdog killed a job because its {@code deadline}
     * passed without an explicit {@code exec_extend} or
     * {@code exec_kill}. Distinct from {@link #EXEC_FINISHED} so
     * engines can reason about lease semantics:
     * {@code EXEC_FINISHED} = subprocess decided, {@code EXEC_TIMEOUT}
     * = we ran out of patience. Payload carries the same
     * {@code jobId}/output as {@code EXEC_FINISHED} plus
     * {@code killedAfterSeconds}.
     */
    EXEC_TIMEOUT
}
