package de.mhus.vance.brain.tools.exec;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Compact status snapshot for a single exec job — no inline stdout/stderr
 * bodies (use {@code work_exec_tail} for that). Cheap enough to call in a poll
 * loop and small enough to fit a long {@code work_exec_list} response.
 */
public record ExecStat(
        String id,
        String projectId,
        String command,
        ExecJob.Status status,
        Instant startedAt,
        Instant lastOutputAt,
        @Nullable Instant finishedAt,
        @Nullable Integer exitCode,
        long durationMs,
        long stdoutBytes,
        long stderrBytes,
        long stdoutMtimeMillis,
        long stderrMtimeMillis,
        String stdoutPath,
        String stderrPath) {}
