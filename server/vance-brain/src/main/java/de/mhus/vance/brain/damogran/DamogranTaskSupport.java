package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.OutputSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared helpers for built-in {@link DamogranTask} beans: reading typed params
 * off a {@link TaskSpec} and resolving declared outputs into
 * {@link OutputArtifact}s (kind/mime from {@link DamogranMime} unless the
 * manifest overrode the kind).
 */
final class DamogranTaskSupport {

    /**
     * Hard-kill wall-clock for an exec task when neither {@code deadlineSeconds}
     * nor {@code timeoutSeconds} is set. Generous (10 min) because a task only
     * blocks until it actually finishes; this is the ceiling before the
     * watchdog kills a hung/runaway command.
     */
    static final int DEFAULT_EXEC_DEADLINE_SECONDS = 600;

    /**
     * Grace added to the block window so the caller waits slightly past the
     * kill deadline and observes the watchdog-killed terminal state (rather than
     * a still-RUNNING snapshot).
     */
    static final int EXEC_KILL_GRACE_SECONDS = 5;

    /**
     * Block window for a {@code deadlineSeconds: 0} (no-kill) exec: effectively
     * "until the command finishes". Large but finite (no {@code currentMillis +
     * waitMs} overflow). Only sensible on an async run — a sync run would block
     * the request; the controller's fast-path wait bounds that.
     */
    static final long NO_DEADLINE_WAIT_MS = Long.MAX_VALUE / 4;

    private DamogranTaskSupport() {}

    /**
     * Hard-kill deadline (seconds) for an exec task: {@code deadlineSeconds},
     * else the {@code timeoutSeconds} alias, else {@link #DEFAULT_EXEC_DEADLINE_SECONDS}.
     */
    static int execDeadlineSeconds(TaskSpec spec) {
        return intOr(spec, "deadlineSeconds", intOr(spec, "timeoutSeconds", DEFAULT_EXEC_DEADLINE_SECONDS));
    }

    static @Nullable String string(TaskSpec spec, String key) {
        Object raw = spec.params().get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    static String requireString(TaskSpec spec, String key) {
        String value = string(spec, key);
        if (value == null) {
            throw new DamogranException(
                    "task '" + spec.type() + "' requires parameter '" + key + "'");
        }
        return value;
    }

    static int intOr(TaskSpec spec, String key, int fallback) {
        Object raw = spec.params().get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new DamogranException(
                        "task '" + spec.type() + "' parameter '" + key + "' must be an integer");
            }
        }
        return fallback;
    }

    /**
     * The shared {@code exec} task: read {@code command}, run it on the run's
     * {@link ComposeExec} backend (WORK or remote), and map the outcome. Used by
     * {@link ExecDamogranTask} and the remote runner alike — one exec task, one
     * result mapping, regardless of target.
     */
    static DamogranTaskResult runExecTask(DamogranContext ctx, TaskSpec spec) {
        String command = requireString(spec, "command");
        ComposeExec.Result result = ctx.requireExec("exec").run(command, execDeadlineSeconds(spec));
        return toResult(result, command, outputsFor(ctx, spec));
    }

    /**
     * Maps a {@link ComposeExec.Result} into a task result. Success =
     * {@code COMPLETED} with exit code 0; otherwise the failure carries the
     * status, exit code and (capped) stderr.
     */
    static DamogranTaskResult toResult(
            ComposeExec.Result result, String command, List<OutputArtifact> outputs) {
        String stdout = result.stdout();
        String stderr = result.stderr();
        String log = stdout.isBlank() ? stderr
                : (stderr.isBlank() ? stdout : stdout + "\n" + stderr);
        if (result.ok()) {
            return DamogranTaskResult.success(outputs, log);
        }
        String detail = stderr.isBlank() ? "" : ": " + cap(stderr);
        return DamogranTaskResult.failure(
                "'" + command + "' status=" + result.status() + " exit=" + result.exitCode() + detail, log);
    }

    /**
     * Declared outputs resolve to workspace artifacts only where a local
     * workspace exists (WORK); CLIENT/DAEMON have no server-side path to source
     * bytes from, so they surface no outputs.
     */
    static List<OutputArtifact> outputsFor(DamogranContext ctx, TaskSpec spec) {
        return ctx.workspacePath() != null ? resolveOutputs(spec) : List.of();
    }

    private static String cap(String s) {
        String trimmed = s.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    /** Resolves the task's declared outputs into renderable artifacts. */
    static List<OutputArtifact> resolveOutputs(TaskSpec spec) {
        List<OutputArtifact> result = new ArrayList<>();
        for (OutputSpec out : spec.declaredOutputs()) {
            String kind = out.kind() != null ? out.kind() : DamogranMime.kindForPath(out.path());
            result.add(new OutputArtifact(
                    out.path(), kind, DamogranMime.mimeForPath(out.path()), out.title()));
        }
        return List.copyOf(result);
    }
}
