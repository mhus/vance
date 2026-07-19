package de.mhus.vance.brain.damogran;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Execution scope handed to a {@link DamogranTask}. A data carrier — it holds
 * the resolved workspace and routing, not services (task beans inject the
 * shared services they delegate to themselves).
 *
 * <p>The runner sets the compose process's WorkTarget before dispatching, so a
 * task that runs a step through the shared layer (via the {@code file_*} /
 * {@code exec_*} wrappers) routes to CLIENT/WORK/DAEMON automatically. The
 * workspace fields here are for tasks that read/write workspace files directly
 * on the pod (WORK target).
 *
 * @param tenantId        tenant scope
 * @param projectId       project scope
 * @param processId       owning think-process id (for tool dispatch / WorkTarget),
 *                        {@code null} for non-process callers
 * @param workspaceName   logical workspace name from the manifest
 * @param workspaceDirName provisioned RootDir name (used as dispatch {@code dirName}
 *                        and in {@code vance-workspace:} URIs)
 * @param workspacePath   on-pod RootDir filesystem path; {@code null} when the
 *                        target is {@code CLIENT} (no server-side path)
 * @param target          WorkTarget kind — {@code CLIENT}, {@code WORK} or {@code DAEMON}
 * @param daemonName      target daemon name, only set when {@code target=DAEMON}
 * @param composeBaseDir  directory of the compose document, used to resolve
 *                        relative {@code vance:} import/export paths ({@code null}
 *                        for inline runs without a document context)
 */
public record DamogranContext(
        String tenantId,
        String projectId,
        @Nullable String processId,
        String workspaceName,
        String workspaceDirName,
        @Nullable Path workspacePath,
        String target,
        @Nullable String daemonName,
        @Nullable String composeBaseDir,
        @Nullable ComposeFileIo fileIo) {

    /** Convenience for callers/tests that don't drive import/export (no file IO). */
    public DamogranContext(
            String tenantId, String projectId, @Nullable String processId,
            String workspaceName, String workspaceDirName, @Nullable Path workspacePath,
            String target, @Nullable String daemonName, @Nullable String composeBaseDir) {
        this(tenantId, projectId, processId, workspaceName, workspaceDirName, workspacePath,
                target, daemonName, composeBaseDir, null);
    }

    /** The run's file backend for import/export; throws if none was bound. */
    public ComposeFileIo requireFileIo(String op) {
        if (fileIo == null) {
            throw new DamogranException(op + ": no file IO bound to this compose run");
        }
        return fileIo;
    }

    public boolean isWork() {
        return "WORK".equals(target);
    }

    public boolean isClient() {
        return "CLIENT".equals(target);
    }

    public boolean isDaemon() {
        return "DAEMON".equals(target);
    }
}
