package de.mhus.vance.shared.workspace;

import de.mhus.vance.api.projects.WorkspaceNodeType;
import de.mhus.vance.api.projects.WorkspaceTreeNodeDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-project workspace orchestrator. See
 * {@code specification/workspace-management.md}.
 *
 * <p>Owns the layout {@code <root>/<tenantId>/<projectId>/<dirName>/}
 * with sibling descriptor {@code <dirName>.json}. The tenant prefix
 * prevents cross-tenant collisions when two tenants happen to use the
 * same project name. Delegates type-specific behaviour to
 * {@link WorkspaceContentHandler}s, addressed by
 * {@link WorkspaceDescriptor#getType()}.
 *
 * <p>Phase 0/1 surface: workspace + RootDir lifecycle, sandboxed
 * read/write/list/delete within a RootDir, lazy temp convenience.
 * Suspend/recover via MongoDB snapshots and quota monitoring come in
 * later phases.
 */
@Service
@Slf4j
public class WorkspaceService {

    private static final String WORKSPACE_FILE = "workspace.json";
    private static final String DESCRIPTOR_SUFFIX = ".json";
    private static final Pattern SAFE_LABEL = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final int MAX_COLLISION_RETRIES = 50;

    private final WorkspaceProperties properties;
    private final Map<String, WorkspaceContentHandler> handlersByType;
    private final ObjectMapper objectMapper;
    private final WorkspaceSnapshotRepository snapshotRepository;

    /** Lazy temp-RootDir per {@code (tenantId, projectId, creatorProcessId)} triple — see §7.3. */
    private final Map<String, String> tempDirCache = new ConcurrentHashMap<>();

    /** Per-{@code (tenantId, projectId, creatorProcessId)} working RootDir — see §7.4. */
    private final Map<String, String> workingDirByCreator = new ConcurrentHashMap<>();

    public WorkspaceService(WorkspaceProperties properties,
                            List<WorkspaceContentHandler> handlers,
                            WorkspaceSnapshotRepository snapshotRepository) {
        this.properties = properties;
        this.snapshotRepository = snapshotRepository;
        Map<String, WorkspaceContentHandler> map = new HashMap<>();
        for (WorkspaceContentHandler h : handlers) {
            WorkspaceContentHandler prev = map.put(h.type(), h);
            if (prev != null) {
                throw new IllegalStateException(
                        "Duplicate WorkspaceContentHandler for type '" + h.type() + "'");
            }
        }
        this.handlersByType = Map.copyOf(map);
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
        log.debug("WorkspaceService bootstrapped: root={} handlers={}",
                properties.getRoot(), handlersByType.keySet());
    }

    // ---------------------------------------------------------------------
    // Workspace lifecycle
    // ---------------------------------------------------------------------

    /**
     * Initialize (or load) the workspace for a project. Creates the
     * folder lazily and writes {@code workspace.json} on first call.
     * If snapshots exist (project was suspended or crashed mid-suspend),
     * recovers them as part of init — the recovered workspace is
     * indistinguishable from one that never suspended. Idempotent.
     */
    public Workspace init(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        List<WorkspaceSnapshotDocument> snapshots = snapshotRepository.findByProjectId(projectId);
        if (!snapshots.isEmpty()) {
            return recoverFromSnapshots(tenantId, projectId, snapshots);
        }
        return initEmpty(tenantId, projectId);
    }

    private Workspace initEmpty(String tenantId, String projectId) {
        Path root = projectFolder(tenantId, projectId);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new WorkspaceException(
                    "Cannot create workspace folder for " + projectId + ": " + e.getMessage(), e);
        }
        Path metaFile = root.resolve(WORKSPACE_FILE);
        if (Files.exists(metaFile)) {
            Workspace existing = readWorkspaceMeta(metaFile);
            existing.setRoot(root);
            return existing;
        }
        Workspace ws = Workspace.builder()
                .tenant(tenantId)
                .projectId(projectId)
                .createdAt(Instant.now().toString())
                .root(root)
                .build();
        writeWorkspaceMeta(metaFile, ws);
        log.info("Workspace initialized: tenant={} projectId={} root={}", tenantId, projectId, root);
        return ws;
    }

    /** Look up an existing workspace without creating one. */
    public Optional<Workspace> get(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        Path metaFile = root.resolve(WORKSPACE_FILE);
        if (!Files.exists(metaFile)) {
            return Optional.empty();
        }
        Workspace ws = readWorkspaceMeta(metaFile);
        ws.setRoot(root);
        return Optional.of(ws);
    }

    /**
     * Tear down a workspace completely: close every RootDir, delete the
     * folder. After this, the workspace must be {@link #init} again
     * before further use.
     */
    public void dispose(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        if (!Files.exists(root)) {
            return;
        }
        for (RootDirHandle h : listRootDirs(tenantId, projectId)) {
            try {
                disposeRootDir(tenantId, projectId, h.getDirName());
            } catch (RuntimeException e) {
                log.warn("Failed to dispose RootDir {}/{}/{}: {}",
                        tenantId, projectId, h.getDirName(), e.toString());
            }
        }
        // Drop creator-cache entries for this project.
        String prefix = creatorCachePrefix(tenantId, projectId);
        tempDirCache.keySet().removeIf(k -> k.startsWith(prefix));
        workingDirByCreator.keySet().removeIf(k -> k.startsWith(prefix));
        // Snapshots (if any from prior suspends) are also gone — dispose is terminal.
        snapshotRepository.deleteByProjectId(projectId);
        deleteRecursively(root);
        log.info("Workspace disposed: tenant={} projectId={}", tenantId, projectId);
    }

    // ---------------------------------------------------------------------
    // Suspend / Recover
    // ---------------------------------------------------------------------

    /**
     * Suspend every RootDir in a project: handler.suspend → snapshot to
     * Mongo → folder + descriptor removed. After this returns, the
     * workspace folder is gone; the next {@link #init} call recovers
     * it from the snapshots. Caller is expected to have stopped all
     * engines first (Project-Lifecycle §11.3).
     */
    public void suspendAll(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        if (!Files.exists(root)) {
            log.debug("suspendAll: workspace folder missing for {}/{}, nothing to suspend",
                    tenantId, projectId);
            return;
        }
        Workspace ws = get(tenantId, projectId).orElse(null);
        String tenant = ws == null ? tenantId : ws.getTenant();
        Instant now = Instant.now();
        for (RootDirHandle handle : listRootDirs(tenantId, projectId)) {
            WorkspaceContentHandler handler = handlersByType.get(handle.getType());
            if (handler == null) {
                throw new WorkspaceException(
                        "No handler registered for type '" + handle.getType()
                                + "' on suspend of " + handle.getDirName());
            }
            try {
                handler.suspend(handle);
            } catch (RuntimeException e) {
                throw new WorkspaceException(
                        "Handler '" + handle.getType() + "' failed to suspend "
                                + handle.getDirName() + ": " + e.getMessage(), e);
            }
            // Persist snapshot first — if we crash before deleting the folder, init's
            // recover-path detects the duplicate state and rebuilds from the snapshot.
            persistSnapshot(tenant, projectId, handle, now);
            // Then remove on-disk content.
            deleteRecursively(handle.getPath());
            try {
                Files.deleteIfExists(handle.getPath().getParent().resolve(
                        handle.getDirName() + DESCRIPTOR_SUFFIX));
            } catch (IOException e) {
                log.warn("Failed to delete descriptor for {}: {}", handle.getDirName(), e.toString());
            }
        }
        // Forget per-creator caches for this project.
        String prefix = creatorCachePrefix(tenantId, projectId);
        tempDirCache.keySet().removeIf(k -> k.startsWith(prefix));
        workingDirByCreator.keySet().removeIf(k -> k.startsWith(prefix));
        // Strip workspace.json + folder so a later init() rehydrates from snapshots.
        try {
            Files.deleteIfExists(root.resolve(WORKSPACE_FILE));
        } catch (IOException e) {
            log.warn("Failed to delete workspace meta on suspend: {}", e.toString());
        }
        deleteRecursively(root);
        log.info("Workspace suspended: tenant={} projectId={}", tenantId, projectId);
    }

    /**
     * Recover all snapshots for a project. Convenience for ProjectService;
     * {@link #init} also auto-recovers when called on a project with
     * snapshots, so this is mostly an alias with explicit semantics.
     * Idempotent — no-op when no snapshots exist.
     */
    public void recoverAll(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        List<WorkspaceSnapshotDocument> snapshots = snapshotRepository.findByProjectId(projectId);
        if (snapshots.isEmpty()) {
            return;
        }
        recoverFromSnapshots(tenantId, projectId, snapshots);
    }

    private Workspace recoverFromSnapshots(String tenantId, String projectId,
                                           List<WorkspaceSnapshotDocument> snapshots) {
        Path root = projectFolder(tenantId, projectId);
        // Blow away any partial folder state from a crashed previous attempt.
        if (Files.exists(root)) {
            log.info("recover: clearing existing workspace folder for {}/{} before re-applying snapshots",
                    tenantId, projectId);
            deleteRecursively(root);
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new WorkspaceException(
                    "Cannot create workspace folder for recover: " + e.getMessage(), e);
        }
        String effectiveTenant = StringUtils.isNotBlank(tenantId)
                ? tenantId
                : snapshots.stream()
                .map(WorkspaceSnapshotDocument::getTenant)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
        Workspace ws = Workspace.builder()
                .tenant(effectiveTenant)
                .projectId(projectId)
                .createdAt(Instant.now().toString())
                .root(root)
                .build();
        writeWorkspaceMeta(root.resolve(WORKSPACE_FILE), ws);

        for (WorkspaceSnapshotDocument snapshot : snapshots) {
            WorkspaceDescriptor descriptor = snapshot.getDescriptor();
            if (descriptor == null) {
                log.warn("Snapshot {}/{} has no descriptor — skipping",
                        snapshot.getProjectId(), snapshot.getDirName());
                continue;
            }
            String dirName = snapshot.getDirName();
            WorkspaceContentHandler handler = handlersByType.get(descriptor.getType());
            if (handler == null) {
                log.warn("No handler for type '{}' on recover of {}/{} — skipping",
                        descriptor.getType(), projectId, dirName);
                continue;
            }
            Path rootDirPath = root.resolve(dirName);
            Path descriptorFile = root.resolve(dirName + DESCRIPTOR_SUFFIX);
            try {
                writeDescriptor(descriptorFile, descriptor);
                Files.createDirectory(rootDirPath);
            } catch (IOException e) {
                throw new WorkspaceException(
                        "Cannot stage RootDir on recover: " + e.getMessage(), e);
            }
            RootDirHandle handle = RootDirHandle.builder()
                    .tenantId(effectiveTenant)
                    .projectId(projectId)
                    .dirName(dirName)
                    .type(descriptor.getType())
                    .path(rootDirPath)
                    .descriptor(descriptor)
                    .build();
            try {
                handler.recover(handle, descriptor);
            } catch (RuntimeException e) {
                // Roll back this RootDir; leave the snapshot in place so a retry
                // attempt can pick up where we left off.
                deleteRecursively(rootDirPath);
                try {
                    Files.deleteIfExists(descriptorFile);
                } catch (IOException ignored) {
                    // ignore; we already failed
                }
                throw new WorkspaceException(
                        "Handler '" + descriptor.getType() + "' failed to recover "
                                + dirName + ": " + e.getMessage(), e);
            }
            rewriteDescriptor(descriptorFile, handle.getDescriptor());
        }
        snapshotRepository.deleteByProjectId(projectId);
        log.info("Workspace recovered from {} snapshot(s): tenant={} projectId={}",
                snapshots.size(), tenantId, projectId);
        return ws;
    }

    private void persistSnapshot(String tenant, String projectId, RootDirHandle handle, Instant now) {
        WorkspaceSnapshotDocument existing = snapshotRepository
                .findByProjectIdAndDirName(projectId, handle.getDirName())
                .orElseGet(WorkspaceSnapshotDocument::new);
        existing.setTenant(tenant);
        existing.setProjectId(projectId);
        existing.setDirName(handle.getDirName());
        existing.setDescriptor(handle.getDescriptor());
        existing.setSuspendedAt(now);
        snapshotRepository.save(existing);
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
        requireTenant(spec.getTenantId());
        requireProject(spec.getProjectId());
        if (StringUtils.isBlank(spec.getType())) {
            throw new WorkspaceException("RootDirSpec.type is required");
        }
        if (StringUtils.isBlank(spec.getCreatorProcessId())) {
            throw new WorkspaceException("RootDirSpec.creatorProcessId is required");
        }
        checkDiskPressure(spec.getTenantId(), spec.getProjectId(), "createRootDir");
        WorkspaceContentHandler handler = handlersByType.get(spec.getType());
        if (handler == null) {
            throw new WorkspaceException("No handler registered for type '" + spec.getType() + "'");
        }
        Workspace ws = ensureWorkspaceFor(spec.getTenantId(), spec.getProjectId());
        Path root = requireRoot(ws);

        String dirName = resolveDirName(root, spec.getLabelHint());
        WorkspaceDescriptor descriptor = WorkspaceDescriptor.builder()
                .tenant(ws.getTenant())
                .projectId(spec.getProjectId())
                .dirName(dirName)
                .label(spec.getLabelHint())
                .type(spec.getType())
                .creatorProcessId(spec.getCreatorProcessId())
                .creatorEngine(spec.getCreatorEngine())
                .sessionId(spec.getSessionId())
                .createdAt(Instant.now().toString())
                .deleteOnCreatorClose(spec.isDeleteOnCreatorClose())
                .metadata(spec.getMetadata())
                .build();

        Path descriptorFile = root.resolve(dirName + DESCRIPTOR_SUFFIX);
        Path rootDirPath = root.resolve(dirName);
        try {
            // Descriptor first — orphan descriptor is recoverable; orphan folder is harder.
            writeDescriptor(descriptorFile, descriptor);
            Files.createDirectory(rootDirPath);
        } catch (IOException e) {
            // Best-effort cleanup of partial state.
            try {
                Files.deleteIfExists(descriptorFile);
            } catch (IOException ignored) {
                // already logged below
            }
            throw new WorkspaceException(
                    "Failed to create RootDir " + dirName + ": " + e.getMessage(), e);
        }

        RootDirHandle handle = RootDirHandle.builder()
                .tenantId(spec.getTenantId())
                .projectId(spec.getProjectId())
                .dirName(dirName)
                .type(spec.getType())
                .path(rootDirPath)
                .descriptor(descriptor)
                .build();
        try {
            handler.init(handle, spec);
        } catch (RuntimeException e) {
            // Handler init failed: roll back the disk state so we don't leave an unusable RootDir.
            try {
                deleteRecursively(rootDirPath);
            } finally {
                try {
                    Files.deleteIfExists(descriptorFile);
                } catch (IOException ignored) {
                    // already logged below
                }
            }
            throw new WorkspaceException(
                    "Handler '" + spec.getType() + "' failed to init " + dirName + ": " + e.getMessage(), e);
        }
        // Handler may have enriched the descriptor (e.g. GitHandler records the actual HEAD).
        rewriteDescriptor(descriptorFile, handle.getDescriptor());
        log.debug("RootDir created: tenant={} projectId={} dirName={} type={} creator={}",
                spec.getTenantId(), spec.getProjectId(), dirName, spec.getType(), spec.getCreatorProcessId());
        return handle;
    }

    /** Tear down a single RootDir. No-op if the folder is gone already. */
    public void disposeRootDir(String tenantId, String projectId, String dirName) {
        requireTenant(tenantId);
        requireProject(projectId);
        Optional<RootDirHandle> opt = getRootDir(tenantId, projectId, dirName);
        if (opt.isEmpty()) {
            return;
        }
        RootDirHandle handle = opt.get();
        WorkspaceContentHandler handler = handlersByType.get(handle.getType());
        if (handler != null) {
            try {
                handler.close(handle);
            } catch (RuntimeException e) {
                log.warn("Handler '{}' close failed for {}/{}/{}: {}",
                        handle.getType(), tenantId, projectId, dirName, e.toString());
            }
        } else {
            log.warn("No handler registered for type '{}' on close of {}/{}/{}",
                    handle.getType(), tenantId, projectId, dirName);
        }
        deleteRecursively(handle.getPath());
        Path descriptorFile = handle.getPath().getParent().resolve(dirName + DESCRIPTOR_SUFFIX);
        try {
            Files.deleteIfExists(descriptorFile);
        } catch (IOException e) {
            log.warn("Failed to delete descriptor {}: {}", descriptorFile, e.toString());
        }
        // Remove temp-cache and working-dir entries pointing to this dirName.
        String prefix = creatorCachePrefix(tenantId, projectId);
        tempDirCache.entrySet().removeIf(en ->
                en.getKey().startsWith(prefix) && dirName.equals(en.getValue()));
        workingDirByCreator.entrySet().removeIf(en ->
                en.getKey().startsWith(prefix) && dirName.equals(en.getValue()));
        log.debug("RootDir disposed: tenant={} projectId={} dirName={}", tenantId, projectId, dirName);
    }

    /**
     * Dispose all RootDirs of a creator that have
     * {@code deleteOnCreatorClose=true}. Safe to call repeatedly.
     */
    public void disposeByCreator(String tenantId, String projectId, String creatorProcessId) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (StringUtils.isBlank(creatorProcessId)) {
            throw new WorkspaceException("creatorProcessId is required");
        }
        for (RootDirHandle h : listRootDirs(tenantId, projectId)) {
            if (creatorProcessId.equals(h.creatorProcessId()) && h.deleteOnCreatorClose()) {
                disposeRootDir(tenantId, projectId, h.getDirName());
            }
        }
    }

    /**
     * Enumerate RootDirs of a project. Skips orphan folders (without
     * descriptor) and orphan descriptors (without folder), logging at
     * warn level.
     */
    public List<RootDirHandle> listRootDirs(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(DESCRIPTOR_SUFFIX))
                    .filter(p -> !p.getFileName().toString().equals(WORKSPACE_FILE))
                    .map(p -> loadHandleFromDescriptor(tenantId, p))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(RootDirHandle::getDirName))
                    .toList();
        } catch (IOException e) {
            throw new WorkspaceException("Cannot list RootDirs for "
                    + tenantId + "/" + projectId + ": " + e.getMessage(), e);
        }
    }

    /** Look up a single RootDir by name. */
    public Optional<RootDirHandle> getRootDir(String tenantId, String projectId, String dirName) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (StringUtils.isBlank(dirName)) {
            throw new WorkspaceException("dirName is required");
        }
        Path root = projectFolder(tenantId, projectId);
        Path descriptorFile = root.resolve(dirName + DESCRIPTOR_SUFFIX);
        if (!Files.exists(descriptorFile)) {
            return Optional.empty();
        }
        return Optional.ofNullable(loadHandleFromDescriptor(tenantId, descriptorFile));
    }

    // ---------------------------------------------------------------------
    // Path operations within a RootDir
    // ---------------------------------------------------------------------

    /** Sandboxed path resolution; throws on escape attempts or unknown RootDir. */
    public Path resolve(String tenantId, String projectId, String dirName, String relativePath) {
        if (StringUtils.isBlank(relativePath)) {
            throw new WorkspaceException("Path is required");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new WorkspaceException("Path contains NUL byte");
        }
        RootDirHandle handle = getRootDir(tenantId, projectId, dirName)
                .orElseThrow(() -> new WorkspaceException(
                        "Unknown RootDir: " + tenantId + "/" + projectId + "/" + dirName));
        Path base = handle.getPath();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new WorkspaceException("Path escapes RootDir: '" + relativePath + "'");
        }
        return resolved;
    }

    /**
     * Write text content at {@code relativePath} inside the named
     * RootDir, creating parents and overwriting.
     */
    public Path write(String tenantId, String projectId, String dirName, String relativePath,
                      @Nullable String content) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.writeString(resolved,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return resolved;
        } catch (IOException e) {
            throw new WorkspaceException("Write failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read text content at {@code relativePath} as UTF-8. Truncates to
     * {@code maxChars} ({@code <= 0} = unlimited).
     */
    public ReadResult read(String tenantId, String projectId, String dirName,
                           String relativePath, int maxChars) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        if (!Files.exists(resolved)) {
            throw new WorkspaceException("Not found: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new WorkspaceException("Not a regular file: " + relativePath);
        }
        try {
            String full = Files.readString(resolved, StandardCharsets.UTF_8);
            if (maxChars > 0 && full.length() > maxChars) {
                return new ReadResult(full.substring(0, maxChars), true, full.length());
            }
            return new ReadResult(full, false, full.length());
        } catch (IOException e) {
            throw new WorkspaceException("Read failed: " + e.getMessage(), e);
        }
    }

    /** Recursive file list inside a RootDir, paths relative to it, sorted. */
    public List<String> list(String tenantId, String projectId, String dirName) {
        RootDirHandle handle = getRootDir(tenantId, projectId, dirName)
                .orElseThrow(() -> new WorkspaceException(
                        "Unknown RootDir: " + tenantId + "/" + projectId + "/" + dirName));
        Path base = handle.getPath();
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(Files::isRegularFile)
                    .map(base::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new WorkspaceException("List failed: " + e.getMessage(), e);
        }
    }

    /** Delete a single file. Returns false if it wasn't there. */
    public boolean delete(String tenantId, String projectId, String dirName, String relativePath) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        try {
            if (Files.isDirectory(resolved)) {
                throw new WorkspaceException(
                        "Refusing to delete a directory: " + relativePath);
            }
            return Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new WorkspaceException("Delete failed: " + e.getMessage(), e);
        }
    }

    /**
     * Like {@link #resolve} but also asserts the file is a regular
     * file. Used by JS-eval and similar tools that need a {@link Path}
     * for native I/O.
     */
    public Path readablePath(String tenantId, String projectId, String dirName, String relativePath) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        if (!Files.isRegularFile(resolved)) {
            throw new WorkspaceException("Not a regular file: " + relativePath);
        }
        return resolved;
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
            children.add(buildDirNode(handle.getDirName(), handle.getDirName(), handle.getPath(), depth - 1));
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
    public WorkspaceTreeNodeDto tree(String tenantId, String projectId, String dirName,
                                     @Nullable String relativePath, int maxDepth) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (StringUtils.isBlank(dirName)) {
            throw new WorkspaceException("dirName is required");
        }
        RootDirHandle handle = getRootDir(tenantId, projectId, dirName)
                .orElseThrow(() -> new WorkspaceException(
                        "Unknown RootDir: " + tenantId + "/" + projectId + "/" + dirName));
        Path target = StringUtils.isBlank(relativePath)
                ? handle.getPath()
                : resolve(tenantId, projectId, dirName, relativePath);
        if (!Files.exists(target)) {
            throw new WorkspaceException("Not found: " + dirName
                    + (StringUtils.isBlank(relativePath) ? "" : "/" + relativePath));
        }
        String displayName = StringUtils.isBlank(relativePath)
                ? dirName
                : target.getFileName().toString();
        String displayPath = StringUtils.isBlank(relativePath)
                ? dirName
                : dirName + "/" + relativePath.replace('\\', '/');
        if (Files.isDirectory(target)) {
            return buildDirNode(displayName, displayPath, target, Math.max(0, maxDepth));
        }
        return buildFileNode(displayName, displayPath, target);
    }

    /**
     * Read a file's full bytes, refusing anything larger than {@code maxBytes}.
     * Throws {@link WorkspaceFileSizeExceededException} when the size limit is
     * exceeded so callers can map it to {@code 413 Payload Too Large}.
     */
    public byte[] readBytes(String tenantId, String projectId, String dirName,
                            String relativePath, long maxBytes) {
        Path resolved = resolve(tenantId, projectId, dirName, relativePath);
        if (!Files.isRegularFile(resolved)) {
            throw new WorkspaceException("Not a regular file: " + relativePath);
        }
        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException e) {
            throw new WorkspaceException("Stat failed: " + e.getMessage(), e);
        }
        if (maxBytes > 0 && size > maxBytes) {
            throw new WorkspaceFileSizeExceededException(
                    "File '" + relativePath + "' is " + size + " bytes; exceeds limit of " + maxBytes,
                    size, maxBytes);
        }
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new WorkspaceException("Read failed: " + e.getMessage(), e);
        }
    }

    private WorkspaceTreeNodeDto buildDirNode(String name, String path, Path dir, int remainingDepth) {
        Instant mtime = readMtime(dir);
        List<WorkspaceTreeNodeDto> children;
        if (remainingDepth <= 0) {
            children = null;
        } else {
            children = new ArrayList<>();
            try (Stream<Path> entries = Files.list(dir)) {
                List<Path> sorted = entries
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path entry : sorted) {
                    String childName = entry.getFileName().toString();
                    String childPath = path.isEmpty() ? childName : path + "/" + childName;
                    if (Files.isDirectory(entry)) {
                        children.add(buildDirNode(childName, childPath, entry, remainingDepth - 1));
                    } else if (Files.isRegularFile(entry)) {
                        children.add(buildFileNode(childName, childPath, entry));
                    }
                    // skip symlinks-to-elsewhere, sockets, etc.
                }
            } catch (IOException e) {
                throw new WorkspaceException("List failed for " + path + ": " + e.getMessage(), e);
            }
        }
        return WorkspaceTreeNodeDto.builder()
                .name(name)
                .path(path)
                .type(WorkspaceNodeType.DIR)
                .size(0L)
                .lastModified(mtime)
                .children(children)
                .build();
    }

    private WorkspaceTreeNodeDto buildFileNode(String name, String path, Path file) {
        long size;
        Instant mtime;
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            size = attrs.size();
            mtime = attrs.lastModifiedTime().toInstant();
        } catch (IOException e) {
            throw new WorkspaceException("Stat failed for " + path + ": " + e.getMessage(), e);
        }
        return WorkspaceTreeNodeDto.builder()
                .name(name)
                .path(path)
                .type(WorkspaceNodeType.FILE)
                .size(size)
                .lastModified(mtime)
                .children(null)
                .build();
    }

    private static @Nullable Instant readMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return null;
        }
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
    public void setWorkingDir(String tenantId, String projectId,
                              String creatorProcessId, String dirName) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (StringUtils.isBlank(creatorProcessId)) {
            throw new WorkspaceException("creatorProcessId is required");
        }
        if (StringUtils.isBlank(dirName)) {
            throw new WorkspaceException("dirName is required");
        }
        if (getRootDir(tenantId, projectId, dirName).isEmpty()) {
            throw new WorkspaceException(
                    "Cannot set working RootDir to non-existent "
                            + tenantId + "/" + projectId + "/" + dirName);
        }
        workingDirByCreator.put(creatorCacheKey(tenantId, projectId, creatorProcessId), dirName);
    }

    /** Look up the working RootDir name for a creator, if set. */
    public Optional<String> getWorkingDir(String tenantId, String projectId, String creatorProcessId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)
                || StringUtils.isBlank(creatorProcessId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                workingDirByCreator.get(creatorCacheKey(tenantId, projectId, creatorProcessId)));
    }

    /** Clear the working RootDir mapping for a creator. */
    public void clearWorkingDir(String tenantId, String projectId, String creatorProcessId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)
                || StringUtils.isBlank(creatorProcessId)) {
            return;
        }
        workingDirByCreator.remove(creatorCacheKey(tenantId, projectId, creatorProcessId));
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
    public RootDirHandle getOrCreateTempRootDir(String tenantId, String projectId,
                                                String creatorProcessId) {
        ensureTempRootDir(tenantId, projectId, creatorProcessId);
        String dirName = tempDirCache.get(creatorCacheKey(tenantId, projectId, creatorProcessId));
        if (dirName == null) {
            throw new WorkspaceException(
                    "Temp RootDir cache missing after ensure for "
                            + tenantId + "/" + projectId + "/" + creatorProcessId);
        }
        return getRootDir(tenantId, projectId, dirName).orElseThrow(() -> new WorkspaceException(
                "Temp RootDir vanished for "
                        + tenantId + "/" + projectId + "/" + creatorProcessId));
    }

    /**
     * Create a temp file inside a per-creator temp RootDir. The
     * RootDir is created lazily and removed when the creator process
     * is disposed via {@link #disposeByCreator}.
     */
    public Path createTempFile(String tenantId, String projectId, String creatorProcessId,
                               @Nullable String prefix, @Nullable String suffix) {
        Path tempRoot = ensureTempRootDir(tenantId, projectId, creatorProcessId);
        checkDiskPressure(tenantId, projectId, "createTempFile");
        try {
            return Files.createTempFile(tempRoot,
                    prefix == null ? "tmp" : prefix,
                    suffix == null ? ".tmp" : suffix);
        } catch (IOException e) {
            throw new WorkspaceException("createTempFile failed: " + e.getMessage(), e);
        }
    }

    /** Like {@link #createTempFile} but returns a directory. */
    public Path createTempDirectory(String tenantId, String projectId, String creatorProcessId,
                                    @Nullable String prefix) {
        Path tempRoot = ensureTempRootDir(tenantId, projectId, creatorProcessId);
        checkDiskPressure(tenantId, projectId, "createTempDirectory");
        try {
            return Files.createTempDirectory(tempRoot, prefix == null ? "tmp" : prefix);
        } catch (IOException e) {
            throw new WorkspaceException("createTempDirectory failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private Path ensureTempRootDir(String tenantId, String projectId, String creatorProcessId) {
        if (StringUtils.isBlank(creatorProcessId)) {
            throw new WorkspaceException("creatorProcessId is required");
        }
        String key = creatorCacheKey(tenantId, projectId, creatorProcessId);
        String dirName = tempDirCache.get(key);
        if (dirName != null) {
            Optional<RootDirHandle> existing = getRootDir(tenantId, projectId, dirName);
            if (existing.isPresent()) {
                return existing.get().getPath();
            }
            tempDirCache.remove(key);
        }
        synchronized (tempDirCache) {
            dirName = tempDirCache.get(key);
            if (dirName != null) {
                Optional<RootDirHandle> existing = getRootDir(tenantId, projectId, dirName);
                if (existing.isPresent()) {
                    return existing.get().getPath();
                }
                tempDirCache.remove(key);
            }
            RootDirSpec spec = RootDirSpec.builder()
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .type(TempHandler.TYPE)
                    .creatorProcessId(creatorProcessId)
                    .labelHint("tmp")
                    .deleteOnCreatorClose(true)
                    .build();
            RootDirHandle handle = createRootDir(spec);
            tempDirCache.put(key, handle.getDirName());
            return handle.getPath();
        }
    }

    private Workspace ensureWorkspaceFor(String tenantId, String projectId) {
        return get(tenantId, projectId).orElseGet(() -> init(tenantId, projectId));
    }

    private Path projectFolder(String tenantId, String projectId) {
        return Path.of(properties.getRoot())
                .toAbsolutePath()
                .normalize()
                .resolve(tenantId)
                .resolve(projectId)
                .normalize();
    }

    private static String creatorCacheKey(String tenantId, String projectId, String creatorProcessId) {
        return tenantId + "/" + projectId + ":" + creatorProcessId;
    }

    private static String creatorCachePrefix(String tenantId, String projectId) {
        return tenantId + "/" + projectId + ":";
    }

    /**
     * Checks the filesystem holding {@code workspace.root} against
     * {@code softLimitPercent} / {@code hardLimitPercent}. Logs a
     * warning at soft, throws at hard. Quiet on filesystems that
     * don't report sizes (sandboxed envs, network mounts).
     */
    private void checkDiskPressure(String tenantId, String projectId, String op) {
        int hard = properties.getHardLimitPercent();
        int soft = properties.getSoftLimitPercent();
        if (hard <= 0 && soft <= 0) {
            return;
        }
        Path probe = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        Path existing = probe;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return;
        }
        long total;
        long usable;
        try {
            FileStore store = Files.getFileStore(existing);
            total = store.getTotalSpace();
            usable = store.getUsableSpace();
        } catch (IOException e) {
            log.debug("Quota probe failed for {}: {}", existing, e.toString());
            return;
        }
        if (total <= 0) {
            return;
        }
        int usedPercent = (int) (((total - usable) * 100L) / total);
        if (hard > 0 && usedPercent >= hard) {
            throw new WorkspaceQuotaExceededException(
                    op + " refused: workspace volume " + usedPercent
                            + "% used (hard limit " + hard + "%, tenantId="
                            + tenantId + ", projectId=" + projectId + ")");
        }
        if (soft > 0 && usedPercent >= soft) {
            log.warn("Workspace volume at {}% used (soft limit {}%) — op={} tenantId={} projectId={} root={}",
                    usedPercent, soft, op, tenantId, projectId, existing);
        }
    }

    private static Path requireRoot(Workspace ws) {
        Path root = ws.getRoot();
        if (root == null) {
            throw new WorkspaceException("Workspace has no resolved root path");
        }
        return root;
    }

    private String resolveDirName(Path workspaceRoot, @Nullable String labelHint) {
        if (StringUtils.isBlank(labelHint)) {
            return UUID.randomUUID().toString();
        }
        String sanitized = labelHint.trim();
        if (!SAFE_LABEL.matcher(sanitized).matches()) {
            // Hint contains invalid chars — fall back to UUID rather than rejecting.
            return UUID.randomUUID().toString();
        }
        if (WORKSPACE_FILE.equals(sanitized + DESCRIPTOR_SUFFIX) || "workspace".equals(sanitized)) {
            sanitized = "workspace-" + sanitized;
        }
        if (!exists(workspaceRoot, sanitized)) {
            return sanitized;
        }
        for (int i = 2; i < MAX_COLLISION_RETRIES; i++) {
            String candidate = sanitized + "-" + i;
            if (!exists(workspaceRoot, candidate)) {
                return candidate;
            }
        }
        return sanitized + "-" + UUID.randomUUID();
    }

    private static boolean exists(Path workspaceRoot, String dirName) {
        return Files.exists(workspaceRoot.resolve(dirName))
                || Files.exists(workspaceRoot.resolve(dirName + DESCRIPTOR_SUFFIX));
    }

    private void writeDescriptor(Path file, WorkspaceDescriptor descriptor) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(descriptor);
        Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    /** Overwrites an existing descriptor file. Used after a handler enriches it. */
    private void rewriteDescriptor(Path file, WorkspaceDescriptor descriptor) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(descriptor);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to rewrite descriptor {}: {}", file, e.toString());
        }
    }

    private @Nullable RootDirHandle loadHandleFromDescriptor(String tenantId, Path descriptorFile) {
        WorkspaceDescriptor d;
        try {
            d = objectMapper.readValue(Files.readString(descriptorFile, StandardCharsets.UTF_8),
                    WorkspaceDescriptor.class);
        } catch (IOException e) {
            log.warn("Cannot parse descriptor {}: {}", descriptorFile, e.toString());
            return null;
        }
        Path workspaceRoot = descriptorFile.getParent();
        if (workspaceRoot == null) {
            log.warn("Descriptor without parent folder: {}", descriptorFile);
            return null;
        }
        Path rootDirPath = workspaceRoot.resolve(d.getDirName());
        if (!Files.isDirectory(rootDirPath)) {
            log.warn("Orphan descriptor (folder missing): {}", descriptorFile);
            return null;
        }
        return RootDirHandle.builder()
                .tenantId(tenantId)
                .projectId(d.getProjectId())
                .dirName(d.getDirName())
                .type(d.getType())
                .path(rootDirPath)
                .descriptor(d)
                .build();
    }

    private void writeWorkspaceMeta(Path file, Workspace ws) {
        try {
            Files.writeString(file,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ws),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot write workspace meta: " + e.getMessage(), e);
        }
    }

    private Workspace readWorkspaceMeta(Path file) {
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Workspace.class);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot read workspace meta: " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Workspace cleanup failed for {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Workspace walk failed for {}: {}", dir, e.toString());
        }
    }

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

    /** Result of {@link #read} — text plus truncation metadata. */
    public record ReadResult(String text, boolean truncated, int totalChars) {}
}
