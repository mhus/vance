package de.mhus.vance.brain.damogran;

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
 * <p>Subclasses supply the WorkTarget to bind and the connectivity pre-check;
 * everything else — the v1 guards ({@code import}/{@code export}/{@code delete}
 * and non-{@code exec} tasks are unsupported), the fixed
 * {@link BaseEngineTools#WORK_TARGET} tool surface, and the halt-on-failure exec
 * loop — is shared here. So there is no per-target branching in the loop.
 */
@Slf4j
abstract class RemoteExecComposeRunner implements ComposeRunner {

    private static final int DEFAULT_DEADLINE_SECONDS = 120;

    private final WorkTargetService workTargetService;
    private final ThinkProcessService thinkProcessService;
    private final ToolDispatcher toolDispatcher;

    protected RemoteExecComposeRunner(WorkTargetService workTargetService,
                                      ThinkProcessService thinkProcessService,
                                      ToolDispatcher toolDispatcher) {
        this.workTargetService = workTargetService;
        this.thinkProcessService = thinkProcessService;
        this.toolDispatcher = toolDispatcher;
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
            DamogranManifest manifest, @Nullable String baseDir) {
        WorkspaceSpec ws = manifest.workspace();

        if (ws.delete() || ws.clear()) {
            throw new DamogranException(target()
                    + " target has no managed workspace — 'delete'/'clear' not supported");
        }
        if (!manifest.imports().isEmpty() || !manifest.exports().isEmpty()) {
            throw new DamogranException(target()
                    + " target does not support 'import'/'export' (v1) — move files with "
                    + "shell commands (curl/git/…) inside exec tasks");
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

        List<DamogranTaskResult> results = new ArrayList<>();
        for (TaskSpec task : manifest.tasks()) {
            DamogranTaskResult result = runExec(tools, task);
            results.add(result);
            if (!result.isSuccess()) {
                log.debug("Damogran {} compose '{}' halted at task '{}': {}",
                        target(), ws.name(), task.type(), result.error());
                return new DamogranComposeResult(
                        DamogranStatus.FAILURE, ws.name(), List.copyOf(results), result.error());
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("command", command.toString());
        Object deadline = task.params().get("deadlineSeconds");
        params.put("deadlineSeconds",
                deadline instanceof Number n ? n.intValue() : DEFAULT_DEADLINE_SECONDS);

        Map<String, Object> out;
        try {
            out = tools.invoke("exec_run", params);
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

    private static String renderOutput(Map<String, Object> result) {
        Object out = result.get("output");
        if (out == null) {
            out = result.get("stdout");
        }
        return out != null ? String.valueOf(out) : String.valueOf(result);
    }
}
