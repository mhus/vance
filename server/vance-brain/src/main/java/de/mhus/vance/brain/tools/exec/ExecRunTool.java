package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.brain.execution.ExecutionOwner;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionStatus;
import de.mhus.vance.brain.tools.workspace.WorkspaceDirResolver;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Runs a shell command in the session workspace. Blocks briefly so
 * short commands return synchronously; longer ones come back still
 * RUNNING and the LLM follows up with {@code exec_status} by id.
 */
@Component
@RequiredArgsConstructor
public class ExecRunTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "command", Map.of(
                            "type", "string",
                            "description",
                                    "Shell command to run (bash via /bin/sh -c on "
                                            + "Linux/macOS, cmd.exe /c on Windows). "
                                            + "Full shell syntax allowed; cwd is the "
                                            + "named workspace RootDir."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional RootDir name to use as cwd. Defaults "
                                            + "to the current process's temp RootDir."),
                    "waitMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Milliseconds to wait for completion before "
                                            + "returning. Default is the server's "
                                            + "configured wait."),
                    "deadlineSeconds", Map.of(
                            "type", "integer",
                            "description",
                                    "Optional hard-kill deadline (seconds from now). "
                                            + "If the subprocess is still running when "
                                            + "the deadline passes, the watchdog kills "
                                            + "it and pushes EXEC_TIMEOUT to your inbox. "
                                            + "Distinct from waitMs (which is just how "
                                            + "long this call blocks): a job can have "
                                            + "deadlineSeconds=3600 with waitMs=1000 — "
                                            + "you'll get the jobId back fast and the "
                                            + "watchdog handles termination. Extend with "
                                            + "exec_check(jobId, ifRunning='extend').")),
            "required", List.of("command"));

    private final ExecManager execManager;
    private final ExecProperties properties;
    private final WorkspaceService workspaceService;
    private final ExecutionRegistryService registry;

    @Override
    public String name() {
        return "exec_run";
    }

    @Override
    public String description() {
        return "Execute a shell command in the session workspace and wait up "
                + "to ~15s for completion. If it finishes in time you get "
                + "status + stdout + stderr; otherwise you get the job id and "
                + "follow up with exec_status. stdoutPath / stderrPath are the "
                + "complete log files on disk — page through them with bounded "
                + "further exec_run calls (head, tail, grep -m, sed -n 'A,Bp')."
                + "\n"
                + "Push-completion: when the job finally exits you also get an "
                + "EXEC_FINISHED event in your inbox (or EXEC_TIMEOUT if the "
                + "watchdog killed it) — no need to keep polling exec_status. "
                + "For genuinely long-running jobs pass deadlineSeconds, then "
                + "use the wakeup_in + exec_check heartbeat pattern to keep "
                + "the lease alive (or let it expire and the watchdog cleans "
                + "up automatically).";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("executive", "side-effect");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Run/kill shell job on user workspace";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawCmd = params == null ? null : params.get("command");
        if (!(rawCmd instanceof String command) || command.isBlank()) {
            throw new ToolException("'command' is required and must be a non-empty string");
        }
        long waitMs = properties.getDefaultWaitMs();
        Object rawWait = params == null ? null : params.get("waitMs");
        if (rawWait instanceof Number n && n.longValue() >= 0) {
            waitMs = n.longValue();
        }
        java.time.Instant deadline = null;
        Object rawDeadline = params == null ? null : params.get("deadlineSeconds");
        if (rawDeadline instanceof Number d && d.longValue() > 0) {
            deadline = java.time.Instant.now().plusSeconds(d.longValue());
        }
        String dirName = WorkspaceDirResolver.resolve(workspaceService, ctx, stringOrNull(params, "dirName"));
        try {
            ExecJob job = execManager.submit(
                    ctx.tenantId(), ctx.projectId(), ctx.processId(), dirName, command, deadline);
            registry.register(new ExecutionRegistryEntry(
                    job.id(),
                    ExecutionOwner.Brain.INSTANCE,
                    ctx.tenantId(),
                    ctx.projectId(),
                    ctx.sessionId(),
                    ctx.processId(),
                    job.command(),
                    dirName,
                    job.startedAt(),
                    job.lastOutputAt(),
                    null,
                    ExecutionStatus.RUNNING,
                    null,
                    job.stdoutFile().toString(),
                    job.stderrFile().toString()));
            execManager.waitFor(job, waitMs);
            return ExecJobRenderer.render(job, properties.getInlineOutputCharCap());
        } catch (ExecException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
