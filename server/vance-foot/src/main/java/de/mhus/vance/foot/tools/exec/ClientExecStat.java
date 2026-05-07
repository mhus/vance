package de.mhus.vance.foot.tools.exec;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Compact status snapshot of a client-side exec job — no inline output
 * bodies. Mirror of the brain's {@code ExecStat}.
 */
record ClientExecStat(
        String id,
        String command,
        @Nullable String sessionId,
        @Nullable String projectId,
        ClientExecJob.Status status,
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
