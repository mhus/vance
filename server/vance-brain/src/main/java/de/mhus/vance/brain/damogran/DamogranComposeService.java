package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.damogran.DamogranManifest.WorkspaceSpec;
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
import org.springframework.stereotype.Service;

/**
 * Damogran compose runner: provision the named workspace, set the process
 * WorkTarget, import documents, run the tasks linearly, export results.
 *
 * <p>Linear by design (no loops/gates/branches — that is Vogon/Magrathea). The
 * run halts at the first failing task; its error rides the
 * {@link DamogranTaskResult} envelope. The workspace is named and
 * <em>session-scoped</em> (created with {@code deleteOnCreatorClose=false} under
 * a stable synthetic creator so it survives the calling process and supports
 * re-runs; it is disposed when the project unloads from the pod).
 *
 * <p>v1 supports target {@code WORK}. See {@code planning/damogran-system.md}.
 */
@Slf4j
@Service
public class DamogranComposeService {

    /**
     * Stable, project-scoped creator id for named compose workspaces — decouples
     * their lifetime from any single think-process (they are disposed on project
     * unload, not on {@code disposeByCreator} of a real process).
     */
    static final String WORKSPACE_CREATOR = "_damogran";

    private final DamogranManifestParser parser;
    private final WorkspaceService workspaceService;
    private final WorkTargetService workTargetService;
    private final DamogranTaskExecutor taskExecutor;
    private final DamogranTransport transport;

    public DamogranComposeService(
            DamogranManifestParser parser,
            WorkspaceService workspaceService,
            WorkTargetService workTargetService,
            DamogranTaskExecutor taskExecutor,
            DamogranTransport transport) {
        this.parser = parser;
        this.workspaceService = workspaceService;
        this.workTargetService = workTargetService;
        this.taskExecutor = taskExecutor;
        this.transport = transport;
    }

    /** Parse and run a compose manifest given as YAML text. */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId, String yaml) {
        return run(tenantId, projectId, processId, yaml, null);
    }

    /**
     * Parse and run a compose YAML.
     *
     * @param baseDir directory of the compose document, for resolving relative
     *                {@code vance:} import/export paths ({@code null} = root-relative)
     */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            String yaml, @Nullable String baseDir) {
        return run(tenantId, projectId, processId, parser.parse(yaml), baseDir);
    }

    /** Run a parsed compose manifest (root-relative {@code vance:} paths). */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId, DamogranManifest manifest) {
        return run(tenantId, projectId, processId, manifest, null);
    }

    /** Run a parsed compose manifest, resolving relative {@code vance:} paths against {@code baseDir}. */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir) {
        WorkspaceSpec ws = manifest.workspace();
        if (!WorkspaceSpec.DEFAULT_TARGET.equals(ws.target())) {
            throw new DamogranException(
                    "compose runner v1 supports target WORK only (was: " + ws.target() + ")");
        }

        // Terminal delete: dispose the named workspace (if any) and stop. No
        // provisioning, no import/tasks/export (the parser rejects those).
        if (ws.delete()) {
            return deleteWorkspace(tenantId, projectId, ws);
        }

        RootDirHandle handle = provision(tenantId, projectId, ws);
        if (processId != null) {
            workTargetService.set(processId, WorkTarget.work(handle.getDirName()));
        }
        DamogranContext ctx = new DamogranContext(
                tenantId, projectId, processId,
                ws.name(), handle.getDirName(), handle.getPath(),
                ws.target(), null, baseDir);

        for (ImportEntry imp : manifest.imports()) {
            transport.doImport(ctx, imp);
        }

        List<DamogranTaskResult> results = new ArrayList<>();
        for (TaskSpec task : manifest.tasks()) {
            DamogranTaskResult result = taskExecutor.dispatch(ctx, task);
            results.add(result);
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
