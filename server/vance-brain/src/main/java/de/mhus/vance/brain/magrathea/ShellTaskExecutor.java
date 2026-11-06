package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaErrorKind;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.brain.tools.exec.ExecLabels;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.runner.RunnerRefusedException;
import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.exec.SubmitOptions;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Shell-task executor (plan §4.2). Runs the command where the project's commands run: on the
 * <b>runner</b> when one is bound to the project (or its tenant), otherwise in the brain's own
 * workspace. The choice is made per call and is not something the workflow states — the same
 * workflow runs on a pod with a workspace and on a shared pod that only has a runner.
 *
 * <p>An executor that runs off the project lane ({@link #runsOffLane()}): it waits for the command on its
 * own thread, so a long build does not hold the lane. A runner that is bound but does not answer is an error
 * ({@code unavailable}), not a reason to run the command on the brain after all — that would put work, and
 * its side effects, where the operator decided they must not be.
 *
 * <h3>The directory</h3>
 * {@code dirName:} names a RootDir of the project. Without it the task gets a RootDir of its own for the
 * length of the command and nothing else: created before the command starts, removed when it has ended
 * (locally through the workspace, on the runner with {@code root.delete}). Steps that hand files to each other
 * name the same {@code dirName}.
 *
 * <h3>Stopping</h3>
 * A stop or an abort does not end the program just by interrupting the thread that waits for it: the job is
 * killed, on the runner with {@code exec.kill}, locally through {@link ExecManager#kill}.
 *
 * <h3>YAML</h3>
 * <pre>
 * run_checks:
 *   type: shell_task
 *   run: "npm test &amp;&amp; npm run lint"
 *   dirName: build              # optional RootDir name; without it a RootDir for this task alone
 *   timeoutSeconds: 1800        # the command is killed when it runs longer; default 30
 *   on: { success: review }
 *   catch: { business_error: debug }
 * </pre>
 *
 * <h3>Outcome mapping</h3>
 * <table>
 *   <tr><th>job status</th><th>exitCode</th><th>workflow outcome</th></tr>
 *   <tr><td>COMPLETED</td><td>0</td><td>{@code success}</td></tr>
 *   <tr><td>COMPLETED</td><td>!= 0</td><td>{@code business_error}</td></tr>
 *   <tr><td>KILLED</td><td>—</td><td>{@code timeout}</td></tr>
 *   <tr><td>FAILED / ORPHANED</td><td>—</td><td>{@code technical_error}</td></tr>
 *   <tr><td>runner refused</td><td>—</td><td>{@code technical_error}</td></tr>
 *   <tr><td>runner not reached</td><td>—</td><td>{@code unavailable}</td></tr>
 * </table>
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ShellTaskExecutor implements MagratheaTypeExecutor {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final String SPEC_RUN = "run";
    private static final String SPEC_DIRNAME = "dirName";

    /** What the runner is given to finish by itself before the first look: most commands are done by then. */
    private static final long RUNNER_FIRST_WAIT_MS = 2_000L;

    /** How long the thread that waits for a runner job pauses between looks, at most. */
    private static final Duration MAX_POLL_PAUSE = Duration.ofSeconds(5);

    /** The runner kills a job at its deadline; this is how long past it we wait before giving up on an answer. */
    private static final Duration DEADLINE_GRACE = Duration.ofSeconds(60);

    /** Prefix of the creator id (and label) of the RootDir a task without a {@code dirName} gets. */
    private static final String TEMP_CREATOR_PREFIX = "magrathea-task-";

    private final ExecManager execManager;
    private final ObjectMapper objectMapper;
    private final RunnerWork runner;
    private final WorkspaceService workspaceService;

    /** Where a running job lives, so that it can be killed. */
    private record Running(boolean onRunner, String jobId) {}

    /**
     * The job of each task that is running, by task id — what {@link #cancelRunning} has to kill, because
     * interrupting the thread that waits for the command does not end the command.
     */
    private final ConcurrentHashMap<String, Running> jobsByTask = new ConcurrentHashMap<>();

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.SHELL_TASK;
    }

    /** A command may run for minutes: it waits off the project lane, so the lane stays free for the project. */
    @Override
    public boolean runsOffLane() {
        return true;
    }

    @Override
    public void cancelRunning(MagratheaTaskContext context) {
        Running running = jobsByTask.remove(context.taskId());
        if (running == null) return;
        boolean killed;
        try {
            killed = running.onRunner()
                    ? Boolean.TRUE.equals(runner.call(scope(context), "exec.kill", Map.of("id", running.jobId()))
                            .get("killed"))
                    : execManager.kill(context.tenantId(), context.projectId(), running.jobId());
        } catch (RuntimeException ex) {
            log.warn(
                    "Magrathea shell_task '{}' cancelled but job {} could not be killed: {}",
                    context.state().name(),
                    running.jobId(),
                    ex.toString());
            return;
        }
        log.info(
                "Magrathea shell_task '{}' cancelled — job {} {}",
                context.state().name(),
                running.jobId(),
                killed ? "killed" : "already ended");
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        String command = state.specString(SPEC_RUN);
        if (command == null) {
            return Optional.of(
                    TaskOutcome.failure("shell_task '" + state.name() + "' is missing required 'run:' field"));
        }
        String dirName = state.specString(SPEC_DIRNAME);
        long waitMs = state.timeoutSeconds() == null ? DEFAULT_TIMEOUT_MS : state.timeoutSeconds() * 1000L;
        try {
            // A runner serves the project: the command runs there. None bound: the brain's own workspace.
            if (runner.available(context.tenantId(), context.projectId())) {
                return Optional.of(runOnRunner(context, command, dirName, waitMs));
            }
            return Optional.of(runLocally(context, command, dirName, waitMs));
        } finally {
            jobsByTask.remove(context.taskId());
        }
    }

    // ───────────────────────────── the brain's workspace ─────────────────────────────

    private TaskOutcome runLocally(
            MagratheaTaskContext context, String command, @Nullable String dirName, long waitMs) {
        MagratheaStateSpec state = context.state();
        String creator = TEMP_CREATOR_PREFIX + context.taskId();
        boolean ownDir = dirName == null || dirName.isBlank();
        Map<String, String> labels = Map.of(
                ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_WORKFLOW,
                ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_SHELL,
                ExecLabels.KEY_RUN_KIND, ExecLabels.RUN_KIND_SHELL);
        try {
            String dir = ownDir
                    ? workspaceService
                            .getOrCreateTempRootDir(context.tenantId(), context.projectId(), creator)
                            .getDirName()
                    : dirName;
            Map<String, Object> result = execManager.submitTrackedAndRender(
                    context.tenantId(),
                    context.projectId(),
                    /* sessionId */ null,
                    /* processId */ null,
                    dir,
                    command,
                    waitMs,
                    SubmitOptions.defaults().withLabels(labels),
                    jobId -> jobsByTask.put(context.taskId(), new Running(false, jobId)));
            return mapOutcome(state, result);
        } catch (RuntimeException ex) {
            // ExecException is package-private; the wider RuntimeException covers it plus any infrastructure
            // failure (no workspace on this pod, an unknown RootDir). All map to technical_error.
            log.warn("Magrathea shell_task '{}' failed to run: {}", state.name(), ex.getMessage());
            return new TaskOutcome(errorKindName(MagratheaErrorKind.TECHNICAL_ERROR), null, ex.getMessage(), null);
        } finally {
            if (ownDir) {
                try {
                    workspaceService.disposeByCreator(context.tenantId(), context.projectId(), creator);
                } catch (RuntimeException ex) {
                    log.warn(
                            "Magrathea shell_task '{}': could not remove its RootDir: {}", state.name(), ex.toString());
                }
            }
        }
    }

    // ───────────────────────────── the runner ─────────────────────────────

    private TaskOutcome runOnRunner(
            MagratheaTaskContext context, String command, @Nullable String dirName, long waitMs) {
        MagratheaStateSpec state = context.state();
        ToolInvocationContext ctx = scope(context);
        String creator = TEMP_CREATOR_PREFIX + context.taskId();
        boolean ownDir = dirName == null || dirName.isBlank();
        String dir = dirName;
        try {
            if (ownDir) {
                Map<String, Object> created = runner.call(
                        ctx, "root.create", Map.of("type", "temp", "creatorProcessId", creator, "label", creator));
                if (!(created.get("dirName") instanceof String name)) {
                    return new TaskOutcome(
                            errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                            null,
                            "The runner created a RootDir but did not name it",
                            null);
                }
                dir = name;
            }
            // The command is given its deadline on the runner, which kills it there: a command that outlives
            // timeoutSeconds is ended, not left running behind a "timeout" outcome.
            long deadlineSeconds = Math.max(1, waitMs / 1000);
            Instant startedAt = Instant.now();
            Map<String, Object> job = runner.call(
                    ctx,
                    "exec.start",
                    Map.of(
                            "command", command,
                            "dirName", dir,
                            "deadlineSeconds", deadlineSeconds,
                            "waitMs", Math.min(RUNNER_FIRST_WAIT_MS, waitMs)));
            if (job.get("id") instanceof String id) {
                jobsByTask.put(context.taskId(), new Running(true, id));
            }
            Instant giveUp = startedAt.plusMillis(waitMs).plus(DEADLINE_GRACE);
            Duration pause = Duration.ofSeconds(1);
            while ("RUNNING".equals(String.valueOf(job.get("status")))) {
                if (Instant.now().isAfter(giveUp)) {
                    return new TaskOutcome(
                            errorKindName(MagratheaErrorKind.TIMEOUT),
                            objectMapper.valueToTree(job),
                            "shell_task '" + state.name() + "' did not end " + DEADLINE_GRACE.toSeconds()
                                    + " s after its deadline",
                            null);
                }
                Thread.sleep(pause);
                pause = pause.multipliedBy(2).compareTo(MAX_POLL_PAUSE) > 0 ? MAX_POLL_PAUSE : pause.multipliedBy(2);
                String id = String.valueOf(job.get("id"));
                if (!"RUNNING"
                        .equals(String.valueOf(
                                runner.call(ctx, "exec.stat", Map.of("id", id)).get("status")))) {
                    // Over: the output is read once, here, not with every look.
                    job = runner.call(ctx, "exec.status", Map.of("id", id));
                }
            }
            return mapOutcome(state, job);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return TaskOutcome.failure("shell_task '" + state.name() + "' was interrupted");
        } catch (RunnerRefusedException ex) {
            log.warn(
                    "Magrathea shell_task '{}' refused by the runner ({}): {}",
                    state.name(),
                    ex.code(),
                    ex.getMessage());
            return new TaskOutcome(errorKindName(MagratheaErrorKind.TECHNICAL_ERROR), null, ex.getMessage(), null);
        } catch (ToolException ex) {
            // The runner is bound but did not answer. Not a reason to run the command on the brain instead.
            log.warn("Magrathea shell_task '{}' could not reach the runner: {}", state.name(), ex.getMessage());
            return new TaskOutcome(errorKindName(MagratheaErrorKind.UNAVAILABLE), null, ex.getMessage(), null);
        } finally {
            if (ownDir && dir != null) {
                try {
                    runner.call(ctx, "root.delete", Map.of("dirName", dir));
                } catch (RuntimeException ex) {
                    log.warn(
                            "Magrathea shell_task '{}': could not remove its runner RootDir '{}': {}",
                            state.name(),
                            dir,
                            ex.toString());
                }
            }
        }
    }

    /** The context a runner call about this task runs in: the project is the scope, the run's user the caller. */
    private static ToolInvocationContext scope(MagratheaTaskContext context) {
        return new ToolInvocationContext(
                context.tenantId(),
                context.projectId(), /* sessionId */
                null, /* processId */
                null,
                context.startedBy());
    }

    private TaskOutcome mapOutcome(MagratheaStateSpec state, Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        Integer exitCode = result.get("exitCode") instanceof Number n ? n.intValue() : null;
        String stderr = String.valueOf(result.getOrDefault("stderr", ""));

        // Build a compact output payload — the workflow's storeAs reads
        // from this, the LLM consumes it elsewhere. We keep stdout +
        // stderr + exitCode + durationMs.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (exitCode != null) payload.put("exitCode", exitCode);
        payload.put("stdout", result.getOrDefault("stdout", ""));
        payload.put("stderr", result.getOrDefault("stderr", ""));
        payload.put("durationMs", result.getOrDefault("durationMs", 0));
        payload.put("execJobId", result.get("id"));

        switch (status) {
            case "COMPLETED":
                if (exitCode != null && exitCode == 0) {
                    return TaskOutcome.successWith(objectMapper.valueToTree(payload));
                }
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.BUSINESS_ERROR),
                        objectMapper.valueToTree(payload),
                        "exit " + exitCode + (stderr.isBlank() ? "" : ": " + truncate(stderr, 240)),
                        null);
            case "KILLED":
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TIMEOUT),
                        objectMapper.valueToTree(payload),
                        "shell_task killed by watchdog",
                        null);
            case "FAILED":
            case "ORPHANED":
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                        objectMapper.valueToTree(payload),
                        "shell_task exec failed (" + status + ")",
                        null);
            case "RUNNING":
                // submitTrackedAndRender returned before the job
                // terminated — the workflow's task-level timeout
                // applies, treat as timeout outcome.
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TIMEOUT),
                        objectMapper.valueToTree(payload),
                        "shell_task wait exhausted (" + state.timeoutSeconds() + "s)",
                        null);
            default:
                return new TaskOutcome(
                        errorKindName(MagratheaErrorKind.TECHNICAL_ERROR),
                        objectMapper.valueToTree(payload),
                        "Unknown ExecJob status: " + status,
                        null);
        }
    }

    private static String errorKindName(MagratheaErrorKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
