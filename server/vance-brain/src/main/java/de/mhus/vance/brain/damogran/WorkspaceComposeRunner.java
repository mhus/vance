package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code target: WORK} — the default runner: provision a named, server-side
 * managed workspace (RootDir on the pod), set the process WorkTarget, import
 * documents, run the tasks linearly, export results.
 *
 * <p>Linear by design (no loops/gates/branches — that is Vogon/Magrathea). The
 * run halts at the first failing task; its error rides the
 * {@link DamogranTaskResult} envelope. The workspace is named and
 * <em>session-scoped</em> (created with {@code deleteOnCreatorClose=false} under
 * a stable synthetic creator so it survives the calling process and supports
 * re-runs; it is disposed when the project unloads from the pod).
 */
@Slf4j
@Component
public class WorkspaceComposeRunner implements ComposeRunner {

    /**
     * Stable, project-scoped creator id for named compose workspaces — decouples
     * their lifetime from any single think-process (they are disposed on project
     * unload, not on {@code disposeByCreator} of a real process).
     */
    static final String WORKSPACE_CREATOR = "_damogran";

    private final WorkspaceService workspaceService;
    private final WorkTargetService workTargetService;
    private final DamogranTaskExecutor taskExecutor;
    private final DamogranTransport transport;
    private final ExecManager execManager;
    private final GitService gitService;

    public WorkspaceComposeRunner(
            WorkspaceService workspaceService,
            WorkTargetService workTargetService,
            DamogranTaskExecutor taskExecutor,
            DamogranTransport transport,
            ExecManager execManager,
            GitService gitService) {
        this.workspaceService = workspaceService;
        this.workTargetService = workTargetService;
        this.taskExecutor = taskExecutor;
        this.transport = transport;
        this.execManager = execManager;
        this.gitService = gitService;
    }

    @Override
    public String target() {
        return WorkspaceSpec.DEFAULT_TARGET; // WORK
    }

    @Override
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir, @Nullable ComposeRun run) {
        WorkspaceSpec ws = manifest.workspace();

        // Terminal delete: dispose the named workspace (if any) and stop. No
        // provisioning, no import/tasks/export (the parser rejects those).
        if (ws.delete()) {
            return deleteWorkspace(tenantId, projectId, ws);
        }

        RootDirHandle handle = provision(tenantId, projectId, ws);
        if (processId != null) {
            workTargetService.set(processId, WorkTarget.work(handle.getDirName()));
        }
        ComposeFileIo io = new WorkspaceFileIo(
                workspaceService, tenantId, projectId, handle.getDirName());
        ComposeExec exec = new WorkspaceComposeExec(
                execManager, tenantId, projectId, handle.getDirName(), processId, run);
        ComposeGit git = new WorkspaceComposeGit(
                workspaceService, gitService, tenantId, projectId, handle.getDirName());
        DamogranContext ctx = new DamogranContext(
                tenantId, projectId, processId,
                ws.name(), handle.getDirName(), handle.getPath(),
                ws.target(), null, baseDir, io, run, exec, git);

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
            DamogranTaskResult result = taskExecutor.dispatch(ctx, task);
            results.add(result);
            if (run != null) {
                run.taskDone(result);
            }
            if (!result.isSuccess()) {
                log.debug("Damogran compose '{}' halted at task '{}': {}",
                        ws.name(), task.type(), result.error());
                return new DamogranComposeResult(
                        DamogranStatus.FAILURE, ws.name(), List.copyOf(results), result.error());
            }
        }

        // Exports run only when every task succeeded.
        for (ExportEntry exp : manifest.exports()) {
            transport.doExport(ctx, exp);
        }

        return new DamogranComposeResult(
                DamogranStatus.SUCCESS, ws.name(), List.copyOf(results), null);
    }

    // ──────────────────── workspace provisioning ────────────────────

    /**
     * Terminal disposal for {@code workspace.delete=true}: remove the named
     * workspace (matched by descriptor label) if it exists. Idempotent — a
     * missing workspace is a no-op success. Type is irrelevant when deleting.
     */
    private DamogranComposeResult deleteWorkspace(String tenantId, String projectId, WorkspaceSpec ws) {
        workspaceService.listRootDirs(tenantId, projectId).stream()
                .filter(h -> ws.name().equals(h.getDescriptor().getLabel()))
                .findFirst()
                .ifPresent(h -> workspaceService.disposeRootDir(tenantId, projectId, h.getDirName()));
        return new DamogranComposeResult(DamogranStatus.SUCCESS, ws.name(), List.of(), null);
    }

    /**
     * Finds the named workspace (by descriptor label) and reuses it, or creates
     * it. {@code clear=true} disposes an existing one first. A type mismatch on
     * reuse is a hard error.
     */
    private RootDirHandle provision(String tenantId, String projectId, WorkspaceSpec ws) {
        Optional<RootDirHandle> existing =
                workspaceService.listRootDirs(tenantId, projectId).stream()
                        .filter(h -> ws.name().equals(h.getDescriptor().getLabel()))
                        .findFirst();

        if (existing.isPresent()) {
            RootDirHandle handle = existing.get();
            if (!ws.clear()) {
                if (!handle.getType().equals(ws.type())) {
                    throw new DamogranException("workspace '" + ws.name()
                            + "' already exists with type '" + handle.getType()
                            + "', requested '" + ws.type() + "'");
                }
                return handle;
            }
            workspaceService.disposeRootDir(tenantId, projectId, handle.getDirName());
        }

        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type(ws.type())
                .creatorProcessId(WORKSPACE_CREATOR)
                .labelHint(ws.name())
                .deleteOnCreatorClose(false)
                .metadata(ws.options())
                .build();
        return workspaceService.createRootDir(spec);
    }
}
