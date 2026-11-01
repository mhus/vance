package de.mhus.vance.shared.workspace;

import de.mhus.vance.api.projects.WorkspaceNodeType;
import de.mhus.vance.api.projects.WorkspaceTreeNodeDto;
import de.mhus.vance.exec.PythonHandler;
import de.mhus.vance.exec.RootDirException;
import de.mhus.vance.exec.RootDirFileSizeExceededException;
import de.mhus.vance.exec.RootDirFiles;
import de.mhus.vance.exec.RootDirHandle;
import de.mhus.vance.exec.RootDirQuotaExceededException;
import de.mhus.vance.exec.RootDirSpec;
import de.mhus.vance.exec.RootDirStore;
import de.mhus.vance.exec.TreeNode;
import de.mhus.vance.exec.Workspace;
import de.mhus.vance.exec.WorkspaceContentHandler;
import de.mhus.vance.shared.runner.RunnerBindingService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Per-project workspace orchestrator of the brain. See
 * {@code specification/workspace-management.md}.
 *
 * <p>The RootDirs on disk — layout {@code <root>/<tenantId>/<projectId>/<dirName>/}
 * with sibling descriptors, handler dispatch, label locks, working and temp
 * RootDirs — are managed by {@link RootDirStore} in {@code vance-exec}, which the
 * runner shares. This service is the Spring side around it: configuration from
 * {@link WorkspaceProperties}, the workspace mode and the brain's exception types. A workspace lives on the disk of the pod that
 * runs the project and is never moved between pods: it survives a suspend or a
 * restart where it is, and only dispose / delete / archive remove it.
 */
@Service
@Slf4j
public class WorkspaceService {

    private final WorkspaceProperties properties;
    private final RootDirStore store;
    private final WorkspaceRootService rootService;
    private final RunnerBindingService runnerBindings;

    public WorkspaceService(
            WorkspaceProperties properties,
            List<WorkspaceContentHandler> handlers,
            WorkspaceRootService rootService,
            RunnerBindingService runnerBindings) {
        this.properties = properties;
        this.runnerBindings = runnerBindings;
        this.rootService = rootService;
        // Settings are read per operation: the properties stay mutable configuration.
        this.store = new RootDirStore(
                () -> new RootDirStore.Settings(
                        Paths.get(properties.getRoot()),
                        properties.getSoftLimitPercent(),
                        properties.getHardLimitPercent()),
                handlers);
        log.debug("WorkspaceService bootstrapped: root={} handlers={}", properties.getRoot(), store.handlerTypes());
    }

    /**
     * Runs {@code action} under a stripe lock keyed by
     * {@code (tenantId, projectId, label)}, so a find-or-create on a RootDir
     * with that label is atomic against a concurrent provision of the same
     * workspace name — e.g. two Damogran compose runs racing on virtual
     * threads. Pod-local by design (projects are single-pod-active).
     */
    public <T> T withLabelLock(String tenantId, String projectId, String label, Supplier<T> action) {
        return store.withLabelLock(tenantId, projectId, label, action);
    }

    // ---------------------------------------------------------------------
    // Workspace lifecycle
    // ---------------------------------------------------------------------

    /**
     * Whether a runner serves the project (its own, else the tenant's). A runner is a
     * second place work can run, independent of {@link #mode()}; prompts read both to
     * tell the model where {@code file_*} / {@code exec_*} can land.
     */
    public boolean runnerAvailable(String tenantId, String projectId) {
        return runnerBindings.resolve(tenantId, projectId).isPresent();
    }

    /** Whether tenants get workspace access on this pod ({@code vance.workspace.mode}). */
    public WorkspaceMode mode() {
        return properties.getMode();
    }

    /**
     * Throws unless this pod offers workspace access. For the access paths that are
     * not tools — exec jobs, compose runs — so they fail with a clear message.
     *
     * @throws WorkspaceException when {@code vance.workspace.mode} is {@code off}
     */
    public void requireAccess(String what) {
        if (!properties.getMode().accessible()) {
            throw new WorkspaceException(
                    what + " is not available: this server has no workspace access" + " (vance.workspace.mode=off)");
        }
    }

    /**
     * Initialize (or load) the workspace for a project. Creates the
     * folder lazily and writes {@code workspace.json} on first call.
     * Idempotent: an existing workspace is loaded as it is.
     */
    public Workspace init(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        return translated(() -> store.init(tenantId, projectId));
    }

    /** Look up an existing workspace without creating one. */
    public Optional<Workspace> get(String tenantId, String projectId) {
        return translated(() -> store.get(tenantId, projectId));
    }

    /**
     * Tear down a workspace completely: close every RootDir, delete the
     * folder. After this, the workspace must be {@link #init} again
     * before further use.
     */
    public void dispose(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        translated(() -> store.dispose(tenantId, projectId));
    }

    // ---------------------------------------------------------------------
    // Project maintenance — delete / rename
    //
    // Separate from dispose() on purpose. dispose() is the lifecycle verb: it
    // ends a workspace so the project can start a fresh one. These two end or
    // move the *project*, and they are tenant-scoped where dispose() is not.
    // ---------------------------------------------------------------------

    /** Whether this machine holds a workspace folder for the project. */
    public boolean existsForProject(String tenantId, String projectId) {
        return translated(() -> store.exists(tenantId, projectId));
    }

    /**
     * Removes the project's workspace for good.
     *
     * <p><b>Local folder only.</b> A workspace lives on the disk of whichever
     * pod ran the project, so an admin shell on another machine finds nothing
     * to delete and says so rather than pretending.
     *
     * @return {@code true} when a folder was removed
     */
    public boolean deleteForProject(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        boolean existed = translated(() -> store.exists(tenantId, projectId));
        translated(() -> store.dispose(tenantId, projectId));
        log.info("Workspace removed for project {}/{}: folder={}", tenantId, projectId, existed);
        return existed;
    }

    /**
     * Moves the project's workspace to a new project name: the folder, the
     * {@code workspace.json} and every RootDir descriptor —
     * see {@link RootDirStore#rename} for what the move cannot fix (absolute paths
     * inside moved files, e.g. a Python virtualenv; {@link #rebuildPythonVenv} is
     * the repair).
     *
     * @return how many files were rewritten (descriptors)
     * @throws WorkspaceException if a folder already sits under the new name —
     *     merging two workspaces is not a rename
     */
    public long renameProject(String tenantId, String projectId, String newProjectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        requireProject(newProjectId);
        long rewritten = translated(() -> store.rename(tenantId, projectId, newProjectId));
        log.info(
                "Workspace moved for project {}: '{}' → '{}' ({} descriptor rewrites)",
                tenantId,
                projectId,
                newProjectId,
                rewritten);
        return rewritten;
    }

    // ---------------------------------------------------------------------
    // RootDir operations
    // ---------------------------------------------------------------------

    /**
     * Create a new RootDir. Resolves a unique {@code dirName} (worker
     * hint preferred, UUID fallback), writes the descriptor, creates
     * the folder, then dispatches to the handler. The spec must carry
     * both {@code tenantId} and {@code projectId}.
     */
    public RootDirHandle createRootDir(RootDirSpec spec) {
        return translated(() -> store.createRootDir(spec));
    }

    /** Tear down a single RootDir. No-op if the folder is gone already. */
    public void disposeRootDir(String tenantId, String projectId, String dirName) {
        translated(() -> store.disposeRootDir(tenantId, projectId, dirName));
    }

    /**
     * Dispose all RootDirs of a creator that have
     * {@code deleteOnCreatorClose=true}. Safe to call repeatedly.
     */
    public void disposeByCreator(String tenantId, String projectId, String creatorProcessId) {
        translated(() -> store.disposeByCreator(tenantId, projectId, creatorProcessId));
    }

    /**
     * Enumerate RootDirs of a project. Skips orphan folders (without
     * descriptor) and orphan descriptors (without folder), logging at
     * warn level.
     */
    public List<RootDirHandle> listRootDirs(String tenantId, String projectId) {
        return translated(() -> store.listRootDirs(tenantId, projectId));
    }

    /**
     * Distinct RootDir handler types present in a project's workspace.
     * Useful for callers that need to know "do we have a python env?"
     * without iterating descriptors twice. Returns an empty set when
     * the workspace folder is missing.
     */
    public Set<String> getRootDirTypes(String tenantId, String projectId) {
        return translated(() -> store.getRootDirTypes(tenantId, projectId));
    }

    /** Look up a single RootDir by name. */
    public Optional<RootDirHandle> getRootDir(String tenantId, String projectId, String dirName) {
        return translated(() -> store.getRootDir(tenantId, projectId, dirName));
    }

    // ---------------------------------------------------------------------
    // Handler-specific service operations
    // ---------------------------------------------------------------------

    /**
     * Rebuild the venv of a Python RootDir with a (possibly different)
     * interpreter. Wipes {@code .venv}, recreates it via the given
     * {@code pythonPath}, and reinstalls from {@code requirements.txt}
     * if present. Source files are untouched. The descriptor is
     * updated with the new {@code pythonPath} and rewritten.
     *
     * <p>Throws {@link WorkspaceException} when the named RootDir is
     * missing, has a non-Python type, or the venv tooling reports a
     * non-zero exit. Synchronous — the call blocks until the venv is
     * ready.
     */
    public void rebuildPythonVenv(String tenantId, String projectId, String dirName, String pythonPath) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (StringUtils.isBlank(dirName)) {
            throw new WorkspaceException("dirName is required");
        }
        if (StringUtils.isBlank(pythonPath)) {
            throw new WorkspaceException("pythonPath is required");
        }
        RootDirHandle handle = getRootDir(tenantId, projectId, dirName)
                .orElseThrow(
                        () -> new WorkspaceException("Unknown RootDir: " + tenantId + "/" + projectId + "/" + dirName));
        if (!PythonHandler.TYPE.equals(handle.getType())) {
            throw new WorkspaceException("rebuildPythonVenv refused: RootDir " + dirName
                    + " has type '" + handle.getType() + "', expected '"
                    + PythonHandler.TYPE + "'");
        }
        WorkspaceContentHandler handler = store.handler(PythonHandler.TYPE).orElse(null);
        if (!(handler instanceof PythonHandler python)) {
            throw new WorkspaceException("PythonHandler is not registered — cannot rebuild venv for " + dirName);
        }
        translated(() -> python.rebuildVenv(handle, pythonPath));
        translated(() -> store.saveDescriptor(handle));
        log.info(
                "python rebuildVenv: tenant={} projectId={} dirName={} pythonPath={}",
                tenantId,
                projectId,
                dirName,
                pythonPath);
    }

    // ---------------------------------------------------------------------
    // Path operations within a RootDir
    // ---------------------------------------------------------------------

    /**
     * Sandboxed path resolution; throws on escape attempts or unknown
     * RootDir. Containment (syntactic {@code ..} collapse + symlink
     * escape) is enforced centrally by {@link WorkspaceRootService}.
     */
    public Path resolve(String tenantId, String projectId, String dirName, String relativePath) {
        RootDirHandle handle = getRootDir(tenantId, projectId, dirName)
                .orElseThrow(
                        () -> new WorkspaceException("Unknown RootDir: " + tenantId + "/" + projectId + "/" + dirName));
        return rootService.resolveWithin(handle.getPath(), relativePath);
    }

    /**
     * Write text content at {@code relativePath} inside the named
     * RootDir, creating parents and overwriting.
     */
    public Path write(
            String tenantId, String projectId, String dirName, String relativePath, @Nullable String content) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        return translated(() -> RootDirFiles.write(resolved, content));
    }

    /**
     * Write a stream's raw bytes at {@code relativePath} inside the named
     * RootDir, creating parents and overwriting. Byte-exact counterpart of
     * {@link #readBytes} — no charset round-trip, so binary payloads
     * (parquet, zip, images) survive the copy unchanged.
     */
    public Path writeStream(String tenantId, String projectId, String dirName, String relativePath, InputStream in) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        return translated(() -> RootDirFiles.writeStream(resolved, in));
    }

    /**
     * Read text content at {@code relativePath} as UTF-8. Truncates to
     * {@code maxChars} ({@code <= 0} = unlimited).
     */
    public ReadResult read(String tenantId, String projectId, String dirName, String relativePath, int maxChars) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        return readResult(translated(() -> RootDirFiles.read(resolved, relativePath, maxChars)));
    }

    /**
     * Line-window variant of {@link #read}: the lines from {@code startLine}
     * (1-based, inclusive) up to {@code maxLines} of them, then the
     * {@code maxChars} cap on that window — see {@link RootDirFiles#readLines}.
     * {@link ReadResult#totalChars()} counts the selected window, not the whole file.
     */
    public ReadResult readLines(
            String tenantId,
            String projectId,
            String dirName,
            String relativePath,
            int maxChars,
            int startLine,
            int maxLines) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        return readResult(
                translated(() -> RootDirFiles.readLines(resolved, relativePath, maxChars, startLine, maxLines)));
    }

    /** Recursive file list inside a RootDir, paths relative to it, sorted. */
    public List<String> list(String tenantId, String projectId, String dirName) {
        RootDirHandle handle = getRootDir(tenantId, projectId, dirName)
                .orElseThrow(
                        () -> new WorkspaceException("Unknown RootDir: " + tenantId + "/" + projectId + "/" + dirName));
        return translated(() -> RootDirFiles.list(handle.getPath()));
    }

    /** Delete a single file. Returns false if it wasn't there. */
    public boolean delete(String tenantId, String projectId, String dirName, String relativePath) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        return translated(() -> RootDirFiles.delete(resolved, relativePath));
    }

    /**
     * Like {@link #resolve} but also asserts the file is a regular
     * file. Used by JS-eval and similar tools that need a {@link Path}
     * for native I/O.
     */
    public Path readablePath(String tenantId, String projectId, String dirName, String relativePath) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        return translated(() -> RootDirFiles.requireRegularFile(resolved, relativePath));
    }

    // ---------------------------------------------------------------------
    // Read-only tree projection (workspace-access endpoints)
    // ---------------------------------------------------------------------

    /**
     * Build a virtual workspace-root node whose children are the project's
     * RootDirs. Used by the workspace-access tree endpoint when no
     * {@code path} is given. {@code maxDepth} controls how deep the listing
     * walks <em>inside</em> each RootDir (depth {@code 1} = list RootDirs only,
     * no contents; depth {@code 2} = list each RootDir's top-level entries;
     * etc). RootDirs are sorted alphabetically.
     */
    public WorkspaceTreeNodeDto treeRoot(String tenantId, String projectId, int maxDepth) {
        requireTenant(tenantId);
        requireProject(projectId);
        int depth = Math.max(1, maxDepth);
        List<WorkspaceTreeNodeDto> children = new ArrayList<>();
        for (RootDirHandle handle : listRootDirs(tenantId, projectId)) {
            children.add(toDto(translated(() ->
                    RootDirFiles.dirNode(handle.getDirName(), handle.getDirName(), handle.getPath(), depth - 1))));
        }
        return WorkspaceTreeNodeDto.builder()
                .name("")
                .path("")
                .type(WorkspaceNodeType.DIR)
                .size(0L)
                .lastModified(null)
                .children(children)
                .build();
    }

    /**
     * Build a tree projection rooted at {@code (dirName, relativePath)}.
     * {@code relativePath} may be empty to address the RootDir itself.
     * Throws {@link WorkspaceException} on unknown RootDir, sandbox escape,
     * or missing target. {@code maxDepth} of {@code 0} returns the node
     * without children; {@code 1} returns its direct children; etc.
     */
    public WorkspaceTreeNodeDto tree(
            String tenantId, String projectId, String dirName, @Nullable String relativePath, int maxDepth) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (StringUtils.isBlank(dirName)) {
            throw new WorkspaceException("dirName is required");
        }
        RootDirHandle handle = getRootDir(tenantId, projectId, dirName)
                .orElseThrow(
                        () -> new WorkspaceException("Unknown RootDir: " + tenantId + "/" + projectId + "/" + dirName));
        Path target = StringUtils.isBlank(relativePath)
                ? handle.getPath()
                : resolve(tenantId, projectId, dirName, relativePath);
        if (!Files.exists(target)) {
            throw new WorkspaceException(
                    "Not found: " + dirName + (StringUtils.isBlank(relativePath) ? "" : "/" + relativePath));
        }
        String displayName = StringUtils.isBlank(relativePath)
                ? dirName
                : target.getFileName().toString();
        String displayPath =
                StringUtils.isBlank(relativePath) ? dirName : dirName + "/" + relativePath.replace('\\', '/');
        if (Files.isDirectory(target)) {
            return toDto(
                    translated(() -> RootDirFiles.dirNode(displayName, displayPath, target, Math.max(0, maxDepth))));
        }
        return toDto(translated(() -> RootDirFiles.fileNode(displayName, displayPath, target)));
    }

    /**
     * Read a file's full bytes, refusing anything larger than {@code maxBytes}.
     * Throws {@link WorkspaceFileSizeExceededException} when the size limit is
     * exceeded so callers can map it to {@code 413 Payload Too Large}.
     */
    public byte[] readBytes(String tenantId, String projectId, String dirName, String relativePath, long maxBytes) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        return translated(() -> RootDirFiles.readBytes(resolved, relativePath, maxBytes));
    }

    private static WorkspaceTreeNodeDto toDto(TreeNode node) {
        List<TreeNode> children = node.children();
        return WorkspaceTreeNodeDto.builder()
                .name(node.name())
                .path(node.path())
                .type(node.directory() ? WorkspaceNodeType.DIR : WorkspaceNodeType.FILE)
                .size(node.size())
                .lastModified(node.lastModified())
                .children(
                        children == null
                                ? null
                                : children.stream().map(WorkspaceService::toDto).toList())
                .build();
    }

    private static ReadResult readResult(RootDirFiles.ReadResult result) {
        return new ReadResult(result.text(), result.truncated(), result.totalChars());
    }

    // ---------------------------------------------------------------------
    // Working-RootDir tracking (per creator)
    // ---------------------------------------------------------------------

    /**
     * Registers a RootDir as the current "working" RootDir for a
     * creator. Subsequent default-resolution by tools picks it up
     * before falling back to the temp RootDir. Cleared automatically
     * when the creator process closes (via the brain-side listener)
     * or explicitly via {@link #clearWorkingDir}.
     */
    public void setWorkingDir(String tenantId, String projectId, String creatorProcessId, String dirName) {
        translated(() -> store.setWorkingDir(tenantId, projectId, creatorProcessId, dirName));
    }

    /** Look up the working RootDir name for a creator, if set. */
    public Optional<String> getWorkingDir(String tenantId, String projectId, String creatorProcessId) {
        return store.getWorkingDir(tenantId, projectId, creatorProcessId);
    }

    /** Clear the working RootDir mapping for a creator. */
    public void clearWorkingDir(String tenantId, String projectId, String creatorProcessId) {
        store.clearWorkingDir(tenantId, projectId, creatorProcessId);
    }

    // ---------------------------------------------------------------------
    // Temp convenience
    // ---------------------------------------------------------------------

    /**
     * Returns the lazy temp RootDir for a creator, creating it on first
     * call. Subsequent calls with the same {@code (tenantId, projectId,
     * creatorProcessId)} triple reuse the same handle. The handle has
     * {@code deleteOnCreatorClose=true} — call
     * {@link #disposeByCreator} on creator-close to clean up.
     */
    public RootDirHandle getOrCreateTempRootDir(String tenantId, String projectId, String creatorProcessId) {
        return translated(() -> store.getOrCreateTempRootDir(tenantId, projectId, creatorProcessId));
    }

    /**
     * Create a temp file inside a per-creator temp RootDir. The
     * RootDir is created lazily and removed when the creator process
     * is disposed via {@link #disposeByCreator}.
     */
    public Path createTempFile(
            String tenantId,
            String projectId,
            String creatorProcessId,
            @Nullable String prefix,
            @Nullable String suffix) {
        return translated(() -> store.createTempFile(tenantId, projectId, creatorProcessId, prefix, suffix));
    }

    /** Like {@link #createTempFile} but returns a directory. */
    public Path createTempDirectory(
            String tenantId, String projectId, String creatorProcessId, @Nullable String prefix) {
        return translated(() -> store.createTempDirectory(tenantId, projectId, creatorProcessId, prefix));
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private static void requireTenant(String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            throw new WorkspaceException("tenantId is required");
        }
    }

    private static void requireProject(String projectId) {
        if (StringUtils.isBlank(projectId)) {
            throw new WorkspaceException("projectId is required");
        }
    }

    /**
     * Runs a {@code vance-exec} operation and turns its plain-Java exceptions into
     * the workspace ones the rest of the brain catches.
     */
    private static <T> T translated(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RootDirFileSizeExceededException e) {
            WorkspaceFileSizeExceededException translated =
                    new WorkspaceFileSizeExceededException(e.getMessage(), e.getFileSize(), e.getLimit());
            translated.initCause(e);
            throw translated;
        } catch (RootDirQuotaExceededException e) {
            WorkspaceQuotaExceededException translated = new WorkspaceQuotaExceededException(e.getMessage());
            translated.initCause(e);
            throw translated;
        } catch (RootDirException e) {
            throw new WorkspaceException(e.getMessage(), e);
        }
    }

    private static void translated(Runnable operation) {
        translated(() -> {
            operation.run();
            return null;
        });
    }

    /** Result of {@link #read} — text plus truncation metadata. */
    public record ReadResult(String text, boolean truncated, int totalChars) {}
}
