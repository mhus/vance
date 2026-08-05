package de.mhus.vance.foot.tools.exec;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Compact status snapshot of a client-side exec job — no inline output
 * bodies. Mirror of the brain's {@code ExecStat}. Public because it is
 * the only view outside consumers (the {@code /ui-exec} browser, the
 * {@code client_exec_stat} tool) get on a job; the mutable
 * {@link ClientExecJob} itself stays package-private.
 */
public record ClientExecStat(
        String id,
        String command,
        @Nullable String sessionId,
        @Nullable String projectId,
        ClientExecStatus status,
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
        String stderrPath,
        boolean timedOut) {}
