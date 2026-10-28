package de.mhus.vance.runner;

import de.mhus.vance.exec.ExecException;
import de.mhus.vance.exec.ExecIsolation;
import de.mhus.vance.exec.ExecJob;
import de.mhus.vance.exec.ExecJobs;
import de.mhus.vance.exec.ExecStat;
import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code exec.start}, {@code exec.status}, {@code exec.stat}, {@code exec.list}, {@code exec.tail},
 * {@code exec.kill} — shell commands in a RootDir, run by {@link ExecJobs}, the job
 * table the brain's own exec runs on. Nothing is pushed: the brain asks while it
 * cares about a job (planning/runner.md §7).
 */
final class ExecOperations {

    /** A start may wait for a quick command; the brain's call timeout bounds this. */
    private static final long MAX_WAIT_MS = 90_000;

    private static final int DEFAULT_TAIL_LINES = 10;
    private static final int DEFAULT_INLINE_CHARS = 20_000;
    private static final int MAX_TAIL_LINES = 500;

    /** Job label naming the RootDir the job was started in; a pinned token only sees its own. */
    private static final String ROOT_LABEL = "rootDir";

    private ExecOperations() {}

    static List<RunnerOperation> all(RunnerWorkspace workspace) {
        return List.of(
                start(workspace),
                status(workspace),
                stat(workspace),
                list(workspace),
                tail(workspace),
                kill(workspace));
    }

    private static RunnerOperation start(RunnerWorkspace workspace) {
        return RootDirOperations.operation("exec.start", call -> {
            OperationArgs args = new OperationArgs(call.args());
            String command = args.text("command");
            workspace.requireDiskSpace("starting a command");
            String dirName = args.rootDir(call.claims());
            var claims = call.claims();
            Path cwd = workspace
                    .store()
                    .getRootDir(claims.tenantId(), claims.projectId(), dirName)
                    .orElseThrow(() -> new OperationException("unknown-rootdir", "Unknown RootDir: " + dirName))
                    .getPath();
            int deadlineSeconds = args.integer("deadlineSeconds", 0);
            long waitMs = Math.min(Math.max(args.integer("waitMs", 0), 0), MAX_WAIT_MS);
            Map<String, String> env;
            try {
                env = workspace.execEnv(claims.tenantId(), claims.projectId());
            } catch (IOException e) {
                throw new OperationException("exec-env", "Cannot prepare the job's home: " + e.getMessage(), e);
            }
            String wrapper = workspace.execWrapper();
            if (wrapper == null && !workspace.allowUnsandboxedExec()) {
                throw new OperationException(
                        "exec-unsandboxed",
                        "This runner has no exec wrapper (VANCE_RUNNER_EXEC_WRAPPER), so commands would share "
                                + "its user, data directory and identity with every project. Ask the operator "
                                + "to configure a wrapper, or to set VANCE_RUNNER_ALLOW_UNSANDBOXED_EXEC=true "
                                + "when the runner pod itself is the boundary.");
            }
            ExecJob job;
            try {
                job = workspace
                        .jobs()
                        .submit(new ExecJobs.Submission(
                                scope(call),
                                claims.projectId(),
                                args.optionalText("ownerProcessId"),
                                command,
                                wrapper == null
                                        ? ExecIsolation.shellArgv(command)
                                        : ExecIsolation.wrap(wrapper, cwd.toString(), env.get("HOME"), command),
                                cwd,
                                env,
                                Map.of(ROOT_LABEL, dirName),
                                deadlineSeconds > 0 ? Instant.now().plusSeconds(deadlineSeconds) : null));
            } catch (ExecException e) {
                throw new OperationException("exec", e.getMessage(), e);
            }
            if (waitMs > 0) {
                job = workspace.jobs().waitFor(job, waitMs);
            }
            return OperationResult.of(render(job, args.integer("inlineChars", DEFAULT_INLINE_CHARS)));
        });
    }

    private static RunnerOperation status(RunnerWorkspace workspace) {
        return RootDirOperations.operation("exec.status", call -> {
            String id = new OperationArgs(call.args()).text("id");
            ExecJob job = visible(workspace, call, id);
            return OperationResult.of(
                    render(job, new OperationArgs(call.args()).integer("inlineChars", DEFAULT_INLINE_CHARS)));
        });
    }

    /**
     * Where a job stands, without its output: what the brain's watcher asks of a running job
     * every few seconds. {@code exec.status} reads the logs to inline them; this does not.
     */
    private static RunnerOperation stat(RunnerWorkspace workspace) {
        return RootDirOperations.operation("exec.stat", call -> {
            String id = new OperationArgs(call.args()).text("id");
            ExecJob job = visible(workspace, call, id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", job.id());
            out.put("status", job.status().name());
            if (job.exitCode() != null) {
                out.put("exitCode", job.exitCode());
            }
            if (job.finishedAt() != null) {
                out.put("finishedAt", job.finishedAt().toString());
            }
            out.put("lastOutputAt", job.lastOutputAt().toString());
            out.put("timedOut", job.killedByWatchdog());
            return OperationResult.of(out);
        });
    }

    private static RunnerOperation list(RunnerWorkspace workspace) {
        return RootDirOperations.operation("exec.list", call -> {
            List<ExecStat> stats = workspace.jobs().list(scope(call)).stream()
                    .filter(job -> inPinnedRoot(call, job))
                    .map(ExecJobs::toStat)
                    .toList();
            return OperationResult.of(Map.of("jobs", stats));
        });
    }

    private static RunnerOperation tail(RunnerWorkspace workspace) {
        return RootDirOperations.operation("exec.tail", call -> {
            OperationArgs args = new OperationArgs(call.args());
            String id = args.text("id");
            int lines = Math.min(Math.max(args.integer("n", DEFAULT_TAIL_LINES), 1), MAX_TAIL_LINES);
            String stream = args.optionalText("stream");
            ExecJobs.Stream which = "stderr".equalsIgnoreCase(stream) ? ExecJobs.Stream.STDERR : ExecJobs.Stream.STDOUT;
            visible(workspace, call, id);
            try {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("stream", which.name().toLowerCase());
                List<String> tail = workspace.jobs().tail(scope(call), id, lines, which);
                out.put("lines", tail);
                out.put("returned", tail.size());
                return OperationResult.of(out);
            } catch (ExecException e) {
                throw new OperationException("unknown-job", e.getMessage(), e);
            }
        });
    }

    private static RunnerOperation kill(RunnerWorkspace workspace) {
        return RootDirOperations.operation("exec.kill", call -> {
            String id = new OperationArgs(call.args()).text("id");
            visible(workspace, call, id);
            return OperationResult.of(
                    Map.of("id", id, "killed", workspace.jobs().kill(scope(call), id)));
        });
    }

    /**
     * The flat shape {@code work_exec_status} returns, so a runner job reads like a
     * local one. The log paths are runner-local and left out; {@code exec.tail}
     * pages through the output instead.
     */
    private static Map<String, Object> render(ExecJob job, int inlineCap) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.id());
        out.put("status", job.status().name());
        out.put("command", job.command());
        Instant end = job.finishedAt() != null ? job.finishedAt() : Instant.now();
        out.put("durationMs", Duration.between(job.startedAt(), end).toMillis());
        out.put("lastOutputAt", job.lastOutputAt().toString());
        if (job.exitCode() != null) {
            out.put("exitCode", job.exitCode());
        }
        if (job.finishedAt() != null) {
            out.put("finishedAt", job.finishedAt().toString());
        }
        // Reached its deadline and was killed for it — the brain tells that apart from a kill by the user.
        if (job.killedByWatchdog()) {
            out.put("timedOut", true);
        }
        String stdout = job.readStdout();
        String stderr = job.readStderr();
        out.put("stdout", truncate(stdout, inlineCap));
        out.put("stderr", truncate(stderr, inlineCap));
        if (stdout.length() > inlineCap || stderr.length() > inlineCap) {
            out.put("truncated", true);
            out.put("hint", "Output truncated. Use exec.tail (n lines of stdout or stderr) to page through it.");
        }
        return out;
    }

    /** 20 % head + 80 % tail: shell errors and exit info sit at the end. */
    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int head = max / 5;
        int tail = max - head;
        return text.substring(0, head)
                + "\n…[truncated, " + (text.length() - head - tail) + " chars omitted]\n"
                + text.substring(text.length() - tail);
    }

    /** Jobs belong to the project a token names; one project never sees another's. */
    private static String scope(Call call) {
        return call.claims().tenantId() + "/" + call.claims().projectId();
    }

    /** The job {@code id} of this project — and, for a pinned token, of its RootDir only. */
    private static ExecJob visible(RunnerWorkspace workspace, Call call, String id) throws OperationException {
        ExecJob job = workspace.jobs().get(scope(call), id).orElseThrow(() -> unknown(id));
        if (!inPinnedRoot(call, job)) {
            throw unknown(id);
        }
        return job;
    }

    private static boolean inPinnedRoot(Call call, ExecJob job) {
        String pinned = call.claims().rootDir();
        return pinned == null || pinned.equals(job.labels().get(ROOT_LABEL));
    }

    private static OperationException unknown(String id) {
        return new OperationException("unknown-job", "Unknown exec job '" + id + "' (not in this project)");
    }
}
