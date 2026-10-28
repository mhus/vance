package de.mhus.vance.brain.execution;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The inbox message that tells a process its exec job has ended — the one shape for a
 * job the brain runs itself and one a runner runs. A job that reached its deadline is an
 * {@code EXEC_TIMEOUT} (the process must not read partial output as a finished run),
 * everything else an {@code EXEC_FINISHED}.
 *
 * @param exitCode   {@code null} when the job was killed or lost before it exited
 * @param finishedAt {@code null} when unknown
 * @param timedOut   the watchdog killed the job at its deadline
 */
public record ExecCompletionEvent(
        String jobId,
        String projectId,
        String status,
        @Nullable Integer exitCode,
        Instant startedAt,
        @Nullable Instant finishedAt,
        boolean timedOut,
        List<String> stdoutTail,
        List<String> stderrTail) {

    public ProcessEventType eventType() {
        return timedOut ? ProcessEventType.EXEC_TIMEOUT : ProcessEventType.EXEC_FINISHED;
    }

    /** The message for {@code ownerProcessId}'s inbox. */
    public PendingMessageDocument toMessage(String ownerProcessId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId);
        payload.put("status", status);
        if (exitCode != null) {
            payload.put("exitCode", exitCode);
        }
        if (finishedAt != null) {
            payload.put("finishedAt", finishedAt.toString());
        }
        payload.put("projectId", projectId);
        payload.put("stdoutTail", stdoutTail);
        payload.put("stderrTail", stderrTail);
        long killedAfterSeconds = Duration.between(startedAt, finishedAt != null ? finishedAt : Instant.now())
                .toSeconds();
        if (timedOut) {
            payload.put("killedAfterSeconds", killedAfterSeconds);
        }
        // The LLM only sees this summary (the payload is not rendered into the <process-event>
        // tag); spell out that a timeout is a KILL, so the model doesn't treat partial output as
        // a completed run.
        String summary = timedOut
                ? "Exec " + jobId + " was KILLED — it timed out after hitting its deadline (ran ~"
                        + killedAfterSeconds + "s" + (exitCode != null ? ", exit " + exitCode : "")
                        + "). The command did NOT finish; it was terminated, so any output is partial."
                        + " Re-run with a larger timeout if it needs more time."
                : "Exec " + jobId + " " + status.toLowerCase() + (exitCode != null ? " (exit " + exitCode + ")" : "");
        return PendingMessageDocument.builder()
                .type(PendingMessageType.PROCESS_EVENT)
                .at(Instant.now())
                .sourceProcessId(ownerProcessId)
                .eventType(eventType())
                .content(summary)
                .payload(payload)
                .eventId(UUID.randomUUID().toString())
                .build();
    }
}
