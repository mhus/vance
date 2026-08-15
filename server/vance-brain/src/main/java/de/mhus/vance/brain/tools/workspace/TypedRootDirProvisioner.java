package de.mhus.vance.brain.tools.workspace;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;

/**
 * Ensures the canonical per-project RootDir of a given type exists, and
 * creates it when it does not.
 *
 * <p><b>Why.</b> Running a script needed two steps: create a typed
 * RootDir, then run in it. A model that skips the first step gets a
 * refusal it cannot act on from the error alone —
 * {@code python_run refused: RootDir 'tmp' has type 'temp', expected
 * 'python'} — and the benchmark recorded exactly that, twenty-one times
 * in one class, without the model ever calling {@code python_create}.
 * The precondition is bookkeeping, not a decision: if a Python script is
 * to run, a Python RootDir is implied.
 *
 * <p>The canonical dir is identified by type plus a reserved,
 * underscore-prefixed label ({@link
 * de.mhus.vance.shared.workspace.PythonHandler#DEFAULT_LABEL},
 * {@link de.mhus.vance.shared.workspace.NodeHandler#DEFAULT_LABEL}), so
 * it stays distinguishable from user-created workspaces and is not
 * listed as user content.
 *
 * <p>An explicitly named {@code dirName} always wins and is never
 * auto-created: naming a specific RootDir is a decision, and silently
 * inventing a differently-typed one behind it would hide a mistake
 * rather than surface it.
 */
public final class TypedRootDirProvisioner {

    private TypedRootDirProvisioner() {
    }

    /**
     * Returns the canonical RootDir of {@code type} labelled
     * {@code label}, creating it when absent.
     *
     * @param metadata seed metadata for the create case (interpreter
     *                 path and the like); ignored when the dir exists
     */
    public static RootDirHandle ensure(
            WorkspaceService workspace,
            ToolInvocationContext ctx,
            String type,
            String label,
            Map<String, Object> metadata) {

        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)) {
            throw new ToolException("Workspace tools require tenant and project scope");
        }

        RootDirHandle existing = find(workspace, tenantId, projectId, type, label);
        if (existing != null) {
            return existing;
        }

        String creator = StringUtils.defaultIfBlank(ctx.processId(), ctx.sessionId());
        if (StringUtils.isBlank(creator)) {
            throw new ToolException(
                    "Workspace tool needs a process or session scope to provision a RootDir");
        }

        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type(type)
                .creatorProcessId(creator)
                .sessionId(ctx.sessionId())
                .labelHint(label)
                // Survives the creating process: the canonical workspace is
                // per project, not per turn. A venv or node_modules tree is
                // expensive to rebuild and pointless to throw away.
                .deleteOnCreatorClose(false)
                .metadata(metadata)
                .build();
        try {
            return workspace.createRootDir(spec);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    /**
     * Resolves the RootDir to work in when the caller named none.
     *
     * <p>Keeps the process's working RootDir when it already has the
     * required type — a model that deliberately set up its workspace
     * stays in it. Falls back to the canonical dir (creating it if
     * needed) when there is no working dir or it has the wrong type,
     * which is where the old code refused instead.
     *
     * @return the dirName to use
     */
    public static String workingDirOfTypeOrProvision(
            WorkspaceService workspace,
            ToolInvocationContext ctx,
            String type,
            String label,
            Map<String, Object> metadata) {

        String creator = StringUtils.defaultIfBlank(ctx.processId(), ctx.sessionId());
        if (StringUtils.isNotBlank(creator)) {
            String workingDir = workspace
                    .getWorkingDir(ctx.tenantId(), ctx.projectId(), creator)
                    .orElse(null);
            if (workingDir != null) {
                RootDirHandle handle = workspace
                        .getRootDir(ctx.tenantId(), ctx.projectId(), workingDir)
                        .orElse(null);
                if (handle != null && type.equals(handle.getType())) {
                    return handle.getDirName();
                }
            }
        }
        return ensure(workspace, ctx, type, label, metadata).getDirName();
    }

    /** The canonical dir of this type, or {@code null} when absent. */
    public static @Nullable RootDirHandle find(
            WorkspaceService workspace,
            String tenantId,
            String projectId,
            String type,
            String label) {
        for (RootDirHandle h : workspace.listRootDirs(tenantId, projectId)) {
            if (!type.equals(h.getType())) {
                continue;
            }
            String existingLabel = h.getDescriptor() == null
                    ? null : h.getDescriptor().getLabel();
            if (label.equals(existingLabel)) {
                return h;
            }
        }
        return null;
    }
}
