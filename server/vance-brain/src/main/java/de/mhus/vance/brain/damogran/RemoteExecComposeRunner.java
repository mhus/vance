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
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Shared base for the remote-execution compose runners (CLIENT / DAEMON). Both
 * run the compose against a remote host's filesystem via the work-target tools —
 * the {@link de.mhus.vance.brain.tools.worktarget.WorkTargetDispatcher} routes
 * {@code exec_run}/{@code file_*} to the connected Foot (CLIENT) or the named
 * daemon (DAEMON) once the process WorkTarget is set. Both are deliberately
 * minimal (match the use case: step through several shell commands on a remote
 * host): <b>no managed workspace</b>, only {@code exec} tasks.
 *
 * <p>The compose backends are bound into the {@link DamogranContext}: file IO
 * via {@link RemoteFileIo}, exec via {@link RemoteComposeExec}, git via
 * {@link RemoteComposeGit}. So the import/export loops are plain transport calls
 * (a {@code git:} entry dispatches to {@link GitImporter}/{@link GitExporter},
 * which delegate to {@code ctx.git()}) and the exec task runs through the same
 * {@link DamogranTaskSupport#runExecTask} as WORK — no git/exec logic duplicated
 * here. {@code vance:}/{@code http:} import/export are text-copy;
 * {@code git:*} runs the host's git via exec; binary copy and vault-backed
 * {@code credentialAlias} stay WORK-only. {@code delete}/{@code clear} (managed
 * workspace) and non-{@code exec} tasks are rejected.
 *
 * <p>Subclasses supply the WorkTarget to bind and the connectivity pre-check;
 * everything else — those guards, the fixed {@link BaseEngineTools#WORK_TARGET}
 * tool surface, and the halt-on-failure loop — is shared here, so there is no
 * per-<em>target</em> (CLIENT vs DAEMON) branching in the loop.
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
        ComposeExec exec = new RemoteComposeExec(tools);
        DamogranContext ctx = new DamogranContext(
                tenantId, projectId, processId, ws.name(), ws.name(), null,
                target(), null, baseDir, new RemoteFileIo(tools), run,
                exec, new RemoteComposeGit(exec, target()));

        for (ImportEntry imp : manifest.imports()) {
            transport.doImport(ctx, imp);
        }

        List<DamogranTaskResult> results = new ArrayList<>();
        List<TaskSpec> tasks = manifest.tasks();
        for (int i = 0; i < tasks.size(); i++) {
            TaskSpec task = tasks.get(i);
            if (run != null) {
                run.startTask(i, task.type());
            }
            DamogranTaskResult result = runTask(ctx, task);
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
            transport.doExport(ctx, exp);
        }
        return new DamogranComposeResult(
                DamogranStatus.SUCCESS, ws.name(), List.copyOf(results), null);
    }

    /** Remote targets run {@code exec} only; other task types have no meaning here. */
    private DamogranTaskResult runTask(DamogranContext ctx, TaskSpec task) {
        if (!"exec".equals(task.type())) {
            return DamogranTaskResult.failure("task type '" + task.type()
                    + "' is not supported on " + target() + " target (only 'exec')");
        }
        return DamogranTaskSupport.runExecTask(ctx, task);
    }
}
