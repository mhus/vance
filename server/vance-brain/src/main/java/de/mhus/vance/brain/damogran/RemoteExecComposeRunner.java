package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.worktarget.BaseEngineTools;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Shared base for the remote-execution compose runners (CLIENT / DAEMON). Both
 * run the compose against a remote host's filesystem via the {@code client_*}
 * tools — the {@link de.mhus.vance.brain.tools.worktarget.WorkTargetDispatcher}
 * routes {@code exec_run} to the connected Foot (CLIENT) or the named daemon
 * (DAEMON) once the process WorkTarget is set. Both are deliberately minimal
 * (match the use case: step through several shell commands on a remote host):
 * <b>no managed workspace</b>, only {@code exec} tasks.
 *
 * <p>Import/export do work here, but remotely: {@code vance:}/{@code http:}
 * entries text-copy through the remote file tools, and {@code git:*} runs the
 * remote host's own {@code git} via {@code exec} (clone/pull on import,
 * commit/push on export — see {@link RemoteGit}); binary copy and vault-backed
 * {@code credentialAlias} stay WORK-only. {@code delete}/{@code clear} (managed
 * workspace) and non-{@code exec} tasks are unsupported.
 *
 * <p>Subclasses supply the WorkTarget to bind and the connectivity pre-check;
 * everything else — those guards, the fixed {@link BaseEngineTools#WORK_TARGET}
 * tool surface, and the halt-on-failure exec loop — is shared here, so there is
 * no per-<em>target</em> (CLIENT vs DAEMON) branching in the loop.
 */
@Slf4j
abstract class RemoteExecComposeRunner implements ComposeRunner {

    private final WorkTargetService workTargetService;
    private final ThinkProcessService thinkProcessService;
    private final ToolDispatcher toolDispatcher;
    private final DamogranTransport transport;

    protected RemoteExecComposeRunner(WorkTargetService workTargetService,
                                      ThinkProcessService thinkProcessService,
                                      ToolDispatcher toolDispatcher,
                                      DamogranTransport transport) {
        this.workTargetService = workTargetService;
        this.thinkProcessService = thinkProcessService;
        this.toolDispatcher = toolDispatcher;
        this.transport = transport;
    }

    /** The WorkTarget to bind for this run (e.g. {@code WorkTarget.client()}). */
    protected abstract WorkTarget workTargetFor(DamogranManifest manifest);

    /**
     * Throw a {@link DamogranException} with a clear message if the remote host
     * (Foot / named daemon) is not reachable for this process.
     */
    protected abstract void requireConnected(
            String tenantId, String projectId, ThinkProcessDocument process, DamogranManifest manifest);

    @Override
    public final DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir, @Nullable ComposeRun run) {
        WorkspaceSpec ws = manifest.workspace();

        if (ws.delete() || ws.clear()) {
            throw new DamogranException(target()
                    + " target has no managed workspace — 'delete'/'clear' not supported");
        }
        if (processId == null) {
            throw new DamogranException(target()
                    + " target requires a session-bound process with a connected remote");
        }

        ThinkProcessDocument process = thinkProcessService.findById(processId)
                .orElseThrow(() -> new DamogranException(
                        target() + " target: process not found: " + processId));
        requireConnected(tenantId, projectId, process, manifest);

        workTargetService.set(processId, workTargetFor(manifest));
        ToolInvocationContext scope = new ToolInvocationContext(
                tenantId, projectId, process.getSessionId(), processId, null);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope, BaseEngineTools.WORK_TARGET);
        // Import/export: vance:/http: text-copy via the remote file tools
        // (RemoteFileIo); git:* runs the remote host's own git via exec (there
        // is no jgit/managed workspace here) — binary copy stays WORK-only.
        DamogranContext ctx = new DamogranContext(
                tenantId, projectId, processId, ws.name(), ws.name(), null,
                target(), null, baseDir, new RemoteFileIo(tools), run);

        for (ImportEntry imp : manifest.imports()) {
            if (RemoteGit.isGit(imp.from())) {
                gitImport(tools, imp);
            } else {
                transport.doImport(ctx, imp);
            }
        }

        List<DamogranTaskResult> results = new ArrayList<>();
        List<TaskSpec> tasks = manifest.tasks();
        for (int i = 0; i < tasks.size(); i++) {
            TaskSpec task = tasks.get(i);
            if (run != null) {
                run.startTask(i, task.type());
            }
            DamogranTaskResult result = runExec(tools, task);
            results.add(result);
            if (run != null) {
                run.taskDone(result);
            }
            if (!result.isSuccess()) {
                log.debug("Damogran {} compose '{}' halted at task '{}': {}",
                        target(), ws.name(), task.type(), result.error());
                return new DamogranComposeResult(
                        DamogranStatus.FAILURE, ws.name(), List.copyOf(results), result.error());
            }
        }

        for (ExportEntry exp : manifest.exports()) {
            if (RemoteGit.isGit(exp.to())) {
                gitExport(tools, exp);
            } else {
                transport.doExport(ctx, exp);
            }
        }
        return new DamogranComposeResult(
                DamogranStatus.SUCCESS, ws.name(), List.copyOf(results), null);
    }

    private DamogranTaskResult runExec(ContextToolsApi tools, TaskSpec task) {
        if (!"exec".equals(task.type())) {
            return DamogranTaskResult.failure("task type '" + task.type()
                    + "' is not supported on " + target() + " target (only 'exec')");
        }
        Object command = task.params().get("command");
        if (command == null || command.toString().isBlank()) {
            return DamogranTaskResult.failure("exec task requires 'command'");
        }
        Map<String, Object> out;
        try {
            out = execRemote(tools, command.toString(), DamogranTaskSupport.execDeadlineSeconds(task));
        } catch (RuntimeException e) {
            return DamogranTaskResult.failure("exec failed: " + e.getMessage());
        }
        Object exit = out.get("exitCode");
        String log = renderOutput(out);
        if (exit instanceof Number n && n.intValue() != 0) {
            return DamogranTaskResult.failure("exec exit code " + n.intValue(), log);
        }
        return DamogranTaskResult.success(List.of(), log);
    }

    /**
     * Run one shell command on the remote host via {@code exec_run}, blocking
     * until it finishes (or is killed at the deadline) so the run never returns
     * while the command is still RUNNING and racing the next step. A
     * non-positive {@code deadlineSeconds} means no kill (only sensible in an
     * async run) — then we block on the no-deadline budget.
     */
    private Map<String, Object> execRemote(ContextToolsApi tools, String command, int deadlineSeconds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("command", command);
        params.put("deadlineSeconds", deadlineSeconds);
        params.put("waitMs", deadlineSeconds <= 0
                ? DamogranTaskSupport.NO_DEADLINE_WAIT_MS
                : (deadlineSeconds + DamogranTaskSupport.EXEC_KILL_GRACE_SECONDS) * 1000L);
        return tools.invoke("exec_run", params);
    }

    // ──────────────────── git:* via remote exec ────────────────────

    private void gitImport(ContextToolsApi tools, ImportEntry entry) {
        rejectCredentialAlias(entry.option("credentialAlias"), "git import");
        if (entry.to() == null || entry.to().isBlank()) {
            throw new DamogranException("git import requires a 'to' target directory");
        }
        String url = DamogranUri.stripGit(entry.from());
        String command = RemoteGit.cloneOrPullCommand(url, entry.to(), entry.option("branch"));
        runGit(tools, command, gitDeadline(entry.option("deadlineSeconds")), "git import " + url);
    }

    private void gitExport(ContextToolsApi tools, ExportEntry entry) {
        rejectCredentialAlias(entry.option("credentialAlias"), "git export");
        if (entry.from() == null || entry.from().isBlank()) {
            throw new DamogranException("git export requires a 'from' working-tree directory");
        }
        String url = DamogranUri.stripGit(entry.to());
        String message = entry.option("message");
        String command = RemoteGit.commitPushCommand(entry.from(), url, entry.option("branch"),
                message != null ? message : "Update from Damogran", entry.boolOption("push", true));
        runGit(tools, command, gitDeadline(entry.option("deadlineSeconds")), "git export " + url);
    }

    /** Run a built git command; a non-zero exit (e.g. no git on PATH) fails the run. */
    private void runGit(ContextToolsApi tools, String command, int deadlineSeconds, String label) {
        Map<String, Object> out;
        try {
            out = execRemote(tools, command, deadlineSeconds);
        } catch (RuntimeException e) {
            throw new DamogranException(label + " failed: " + e.getMessage(), e);
        }
        Object exit = out.get("exitCode");
        if (exit instanceof Number n && n.intValue() != 0) {
            throw new DamogranException(label + " exit code " + n.intValue()
                    + ": " + renderOutput(out).strip());
        }
    }

    private void rejectCredentialAlias(@Nullable String alias, String op) {
        if (alias != null && !alias.isBlank()) {
            throw new DamogranException(op + " on " + target() + " target does not support "
                    + "'credentialAlias' (vault-backed, WORK-only) — the remote host's own git "
                    + "credentials (ssh key / credential helper) are used");
        }
    }

    private static int gitDeadline(@Nullable String opt) {
        if (opt == null || opt.isBlank()) {
            return DamogranTaskSupport.DEFAULT_EXEC_DEADLINE_SECONDS;
        }
        try {
            return Integer.parseInt(opt.trim());
        } catch (NumberFormatException e) {
            return DamogranTaskSupport.DEFAULT_EXEC_DEADLINE_SECONDS;
        }
    }

    private static String renderOutput(Map<String, Object> result) {
        Object out = result.get("output");
        if (out == null) {
            out = result.get("stdout");
        }
        return out != null ? String.valueOf(out) : String.valueOf(result);
    }
}
