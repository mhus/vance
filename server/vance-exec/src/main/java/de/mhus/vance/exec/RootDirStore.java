package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The RootDirs of workspaces on disk — the plain-Java core of the brain's
 * {@code WorkspaceService} and of the runner's workspace
 * ({@code planning/runner.md}).
 *
 * <p>Owns the layout {@code <root>/<tenantId>/<projectId>/<dirName>/} with the
 * sibling descriptor {@code <dirName>.json} and the project's
 * {@code workspace.json}. The tenant prefix prevents cross-tenant collisions when
 * two tenants use the same project name. Type-specific behaviour is delegated to
 * {@link WorkspaceContentHandler}s, addressed by {@link WorkspaceDescriptor#getType()}.
 * The descriptor files are the source of truth for handler dispatch, recovery and
 * cleanup.
 *
 * <p>No persistence beyond the disk: where a suspended workspace is kept (the
 * brain parks it in Mongo) is the caller's concern, handed in through
 * {@link #suspendAll} and {@link #recover}. Failures are {@link RootDirException}s.
 *
 * <p>The settings are read per operation, so a caller can keep them in mutable
 * configuration.
 */
@Slf4j
public final class RootDirStore {

    public static final String WORKSPACE_FILE = "workspace.json";
    public static final String DESCRIPTOR_SUFFIX = ".json";

    private static final Pattern SAFE_LABEL = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[a-zA-Z0-9_][a-zA-Z0-9._-]*");
    private static final int MAX_COLLISION_RETRIES = 50;
    /** Fixed set of stripe monitors for {@link #withLabelLock} — bounded, no map growth. */
    private static final int LABEL_LOCK_STRIPES = 64;

    /**
     * @param root             all workspaces live below it
     * @param softLimitPercent volume fill above which operations log a warning ({@code <= 0} = off)
     * @param hardLimitPercent volume fill above which creating is refused ({@code <= 0} = off)
     */
    public record Settings(Path root, int softLimitPercent, int hardLimitPercent) {}

    /** A suspended RootDir, to bring back by {@link #recover}. */
    public record Snapshot(String dirName, WorkspaceDescriptor descriptor) {}

    private final Supplier<Settings> settings;
    private final Map<String, WorkspaceContentHandler> handlersByType;
    private final ObjectMapper objectMapper =
            JsonMapper.builder().findAndAddModules().build();

    /** Lazy temp-RootDir per {@code (tenantId, projectId, creatorProcessId)} triple. */
    private final Map<String, String> tempDirCache = new ConcurrentHashMap<>();

    /** Per-{@code (tenantId, projectId, creatorProcessId)} working RootDir. */
    private final Map<String, String> workingDirByCreator = new ConcurrentHashMap<>();

    private final Object[] labelLocks = newStripes(LABEL_LOCK_STRIPES);

    public RootDirStore(Supplier<Settings> settings, Collection<WorkspaceContentHandler> handlers) {
        this.settings = settings;
        Map<String, WorkspaceContentHandler> map = new HashMap<>();
        for (WorkspaceContentHandler h : handlers) {
            WorkspaceContentHandler prev = map.put(h.type(), h);
            if (prev != null) {
                throw new IllegalStateException("Duplicate WorkspaceContentHandler for type '" + h.type() + "'");
            }
        }
        this.handlersByType = Map.copyOf(map);
    }

    private static Object[] newStripes(int n) {
        Object[] a = new Object[n];
        for (int i = 0; i < n; i++) {
            a[i] = new Object();
        }
        return a;
    }

    /** The handler registered for {@code type}, if any. */
    public Optional<WorkspaceContentHandler> handler(String type) {
        return Optional.ofNullable(handlersByType.get(type));
    }

    /** Registered handler keys. */
    public Set<String> handlerTypes() {
        return handlersByType.keySet();
    }

    /**
     * Runs {@code action} under a stripe lock keyed by
     * {@code (tenantId, projectId, label)}, so a find-or-create on a RootDir with
     * that label is atomic against a concurrent provision of the same workspace
     * name — e.g. two compose runs racing on virtual threads, which otherwise both
     * saw "no RootDir" and created duplicate dirs with the same label. Process-local
     * by design (RootDir provisioning is project-scoped and projects are
     * single-pod-active).
     */
    public <T> T withLabelLock(String tenantId, String projectId, String label, Supplier<T> action) {
        String key = tenantId + '\0' + projectId + '\0' + label;
        Object lock = labelLocks[Math.floorMod(key.hashCode(), labelLocks.length)];
        synchronized (lock) {
            return action.get();
        }
    }

    // ---------------------------------------------------------------------
    // Workspace lifecycle
    // ---------------------------------------------------------------------

    /**
     * Creates the project's workspace folder and its {@code workspace.json} on the
     * first call and loads it afterwards. Idempotent.
     */
    public Workspace init(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RootDirException("Cannot create workspace folder for " + projectId + ": " + e.getMessage(), e);
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

    /** Looks up an existing workspace without creating one. */
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

    /** Whether this machine holds a workspace folder for the project. */
    public boolean exists(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        return Files.exists(projectFolder(tenantId, projectId));
    }

    /**
     * Tears the workspace down completely: closes every RootDir, deletes the
     * folder. No-op when there is no folder. Afterwards the workspace must be
     * {@link #init}ialised again before further use.
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
                log.warn("Failed to dispose RootDir {}/{}/{}: {}", tenantId, projectId, h.getDirName(), e.toString());
            }
        }
        forgetCreatorCaches(tenantId, projectId);
        deleteRecursively(root);
        log.info("Workspace disposed: tenant={} projectId={}", tenantId, projectId);
    }

    /**
     * Moves the project's workspace to a new project name: the folder, the
     * {@code workspace.json} and every RootDir descriptor. A no-op returning
     * {@code 0} when there is no folder.
     *
     * <p>The folder path is {@code <root>/<tenant>/<project>}, so the project name
     * is part of it — leaving it behind would strand a directory that nothing
     * addresses any more.
     *
     * <p><b>What this cannot fix:</b> absolute paths <em>inside</em> the moved
     * files. A Python virtualenv records its own location in its scripts and stops
     * working after the move; rebuilding the venv is the repair. Rewriting
     * arbitrary file content to chase a rename is not something this will attempt.
     *
     * @return how many metadata files were rewritten
     * @throws RootDirException if a folder already sits under the new name —
     *     merging two workspaces is not a rename
     */
    public long rename(String tenantId, String projectId, String newProjectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        requireProject(newProjectId);
        Path from = projectFolder(tenantId, projectId);
        Path to = projectFolder(tenantId, newProjectId);
        if (!Files.exists(from)) {
            return 0;
        }
        if (Files.exists(to)) {
            throw new RootDirException("Workspace folder for '" + newProjectId + "' already exists: " + to);
        }
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
        } catch (IOException e) {
            throw new RootDirException("Cannot move workspace " + from + " → " + to + ": " + e.getMessage(), e);
        }
        long rewritten = rewriteWorkspaceMetadata(tenantId, newProjectId, to);
        forgetCreatorCaches(tenantId, projectId);
        return rewritten;
    }

    /** Rewrites {@code workspace.json} and every descriptor to the new name. */
    private long rewriteWorkspaceMetadata(String tenantId, String newProjectId, Path root) {
        long rewritten = 0;
        Path metaFile = root.resolve(WORKSPACE_FILE);
        if (Files.exists(metaFile)) {
            Workspace ws = readWorkspaceMeta(metaFile);
            ws.setProjectId(newProjectId);
            ws.setRoot(root);
            try {
                Files.deleteIfExists(metaFile);
                writeWorkspaceMeta(metaFile, ws);
                rewritten++;
            } catch (IOException e) {
                log.warn("Failed to rewrite {}: {}", metaFile, e.toString());
            }
        }
        for (RootDirHandle handle : listRootDirs(tenantId, newProjectId)) {
            WorkspaceDescriptor descriptor = handle.getDescriptor();
            descriptor.setProjectId(newProjectId);
            rewriteDescriptor(root.resolve(handle.getDirName() + DESCRIPTOR_SUFFIX), descriptor);
            rewritten++;
        }
        return rewritten;
    }

    // ---------------------------------------------------------------------
    // Suspend / recover — the disk side; the caller keeps the snapshots
    // ---------------------------------------------------------------------

    /**
     * Suspends every RootDir of the project: {@code handler.suspend} →
     * {@code persist} → folder and descriptor removed. When this returns, the
     * workspace folder is gone; {@link #recover} brings it back from whatever
     * {@code persist} kept. The caller is expected to have stopped all engines
     * first.
     *
     * <p>{@code persist} runs <em>before</em> the RootDir's content is deleted — if
     * the process dies in between, the next recover finds both and rebuilds from
     * the persisted snapshot.
     */
    public void suspendAll(String tenantId, String projectId, Consumer<RootDirHandle> persist) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        if (!Files.exists(root)) {
            log.debug("suspendAll: workspace folder missing for {}/{}, nothing to suspend", tenantId, projectId);
            return;
        }
        for (RootDirHandle handle : listRootDirs(tenantId, projectId)) {
            WorkspaceContentHandler handler = handlersByType.get(handle.getType());
            if (handler == null) {
                throw new RootDirException("No handler registered for type '" + handle.getType() + "' on suspend of "
                        + handle.getDirName());
            }
            try {
                handler.suspend(handle);
            } catch (RuntimeException e) {
                throw new RootDirException(
                        "Handler '" + handle.getType() + "' failed to suspend " + handle.getDirName() + ": "
                                + e.getMessage(),
                        e);
            }
            persist.accept(handle);
            deleteRecursively(handle.getPath());
            try {
                Files.deleteIfExists(handle.getPath().getParent().resolve(handle.getDirName() + DESCRIPTOR_SUFFIX));
            } catch (IOException e) {
                log.warn("Failed to delete descriptor for {}: {}", handle.getDirName(), e.toString());
            }
        }
        forgetCreatorCaches(tenantId, projectId);
        // Strip workspace.json + folder so a later init rehydrates from the snapshots.
        try {
            Files.deleteIfExists(root.resolve(WORKSPACE_FILE));
        } catch (IOException e) {
            log.warn("Failed to delete workspace meta on suspend: {}", e.toString());
        }
        deleteRecursively(root);
        log.info("Workspace suspended: tenant={} projectId={}", tenantId, projectId);
    }

    /**
     * Rebuilds the project's workspace from {@code snapshots}: a fresh folder and
     * {@code workspace.json}, then per snapshot descriptor + folder +
     * {@code handler.recover}. A snapshot whose type has no handler is skipped with
     * a warning; a handler that fails rolls back its RootDir and aborts, so the
     * caller can retry with the snapshots still in place.
     */
    public Workspace recover(String tenantId, String projectId, List<Snapshot> snapshots) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        // Blow away any partial folder state from a crashed previous attempt.
        if (Files.exists(root)) {
            log.info(
                    "recover: clearing existing workspace folder for {}/{} before re-applying snapshots",
                    tenantId,
                    projectId);
            deleteRecursively(root);
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RootDirException("Cannot create workspace folder for recover: " + e.getMessage(), e);
        }
        Workspace ws = Workspace.builder()
                .tenant(tenantId)
                .projectId(projectId)
                .createdAt(Instant.now().toString())
                .root(root)
                .build();
        writeWorkspaceMeta(root.resolve(WORKSPACE_FILE), ws);

        for (Snapshot snapshot : snapshots) {
            WorkspaceDescriptor descriptor = snapshot.descriptor();
            String dirName = snapshot.dirName();
            WorkspaceContentHandler handler = handlersByType.get(descriptor.getType());
            if (handler == null) {
                log.warn(
                        "No handler for type '{}' on recover of {}/{} — skipping",
                        descriptor.getType(),
                        projectId,
                        dirName);
                continue;
            }
            Path rootDirPath = root.resolve(dirName);
            Path descriptorFile = root.resolve(dirName + DESCRIPTOR_SUFFIX);
            try {
                writeDescriptor(descriptorFile, descriptor);
                Files.createDirectory(rootDirPath);
            } catch (IOException e) {
                throw new RootDirException("Cannot stage RootDir on recover: " + e.getMessage(), e);
            }
            RootDirHandle handle = RootDirHandle.builder()
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .dirName(dirName)
                    .type(descriptor.getType())
                    .path(rootDirPath)
                    .descriptor(descriptor)
                    .build();
            try {
                handler.recover(handle, descriptor);
            } catch (RuntimeException e) {
                // Roll back this RootDir; the caller keeps its snapshot so a retry can pick up.
                deleteRecursively(rootDirPath);
                try {
                    Files.deleteIfExists(descriptorFile);
                } catch (IOException ignored) {
                    // ignore; we already failed
                }
                throw new RootDirException(
                        "Handler '" + descriptor.getType() + "' failed to recover " + dirName + ": " + e.getMessage(),
                        e);
            }
            rewriteDescriptor(descriptorFile, handle.getDescriptor());
        }
        log.info(
                "Workspace recovered from {} snapshot(s): tenant={} projectId={}",
                snapshots.size(),
                tenantId,
                projectId);
        return ws;
    }

    // ---------------------------------------------------------------------
    // RootDir operations
    // ---------------------------------------------------------------------

    /**
     * Creates a new RootDir. Resolves a unique {@code dirName} (the worker's hint
     * preferred, a UUID otherwise), writes the descriptor, creates the folder, then
     * dispatches to the handler. The spec must carry both tenant and project.
     */
    public RootDirHandle createRootDir(RootDirSpec spec) {
        requireTenant(spec.getTenantId());
        requireProject(spec.getProjectId());
        if (isBlank(spec.getType())) {
            throw new RootDirException("RootDirSpec.type is required");
        }
        if (isBlank(spec.getCreatorProcessId())) {
            throw new RootDirException("RootDirSpec.creatorProcessId is required");
        }
        checkDiskPressure(spec.getTenantId(), spec.getProjectId(), "createRootDir");
        WorkspaceContentHandler handler = handlersByType.get(spec.getType());
        if (handler == null) {
            throw new RootDirException("No handler registered for type '" + spec.getType() + "'");
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
            // Descriptor first — an orphan descriptor is recoverable; an orphan folder is harder.
            writeDescriptor(descriptorFile, descriptor);
            Files.createDirectory(rootDirPath);
        } catch (IOException e) {
            // Best-effort cleanup of partial state.
            try {
                Files.deleteIfExists(descriptorFile);
            } catch (IOException ignored) {
                // nothing more to do
            }
            throw new RootDirException("Failed to create RootDir " + dirName + ": " + e.getMessage(), e);
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
                    // nothing more to do
                }
            }
            throw new RootDirException(
                    "Handler '" + spec.getType() + "' failed to init " + dirName + ": " + e.getMessage(), e);
        }
        // The handler may have enriched the descriptor (e.g. git records the actual HEAD).
        rewriteDescriptor(descriptorFile, handle.getDescriptor());
        log.debug(
                "RootDir created: tenant={} projectId={} dirName={} type={} creator={}",
                spec.getTenantId(),
                spec.getProjectId(),
                dirName,
                spec.getType(),
                spec.getCreatorProcessId());
        return handle;
    }

    /** Tears down a single RootDir. No-op if the folder is gone already. */
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
                log.warn(
                        "Handler '{}' close failed for {}/{}/{}: {}",
                        handle.getType(),
                        tenantId,
                        projectId,
                        dirName,
                        e.toString());
            }
        } else {
            log.warn(
                    "No handler registered for type '{}' on close of {}/{}/{}",
                    handle.getType(),
                    tenantId,
                    projectId,
                    dirName);
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
        tempDirCache.entrySet().removeIf(en -> en.getKey().startsWith(prefix) && dirName.equals(en.getValue()));
        workingDirByCreator.entrySet().removeIf(en -> en.getKey().startsWith(prefix) && dirName.equals(en.getValue()));
        log.debug("RootDir disposed: tenant={} projectId={} dirName={}", tenantId, projectId, dirName);
    }

    /**
     * Disposes all RootDirs of a creator that have {@code deleteOnCreatorClose=true}.
     * Safe to call repeatedly.
     */
    public void disposeByCreator(String tenantId, String projectId, String creatorProcessId) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (isBlank(creatorProcessId)) {
            throw new RootDirException("creatorProcessId is required");
        }
        for (RootDirHandle h : listRootDirs(tenantId, projectId)) {
            if (creatorProcessId.equals(h.creatorProcessId()) && h.deleteOnCreatorClose()) {
                disposeRootDir(tenantId, projectId, h.getDirName());
            }
        }
    }

    /**
     * Enumerates the RootDirs of a project. Skips orphan folders (without
     * descriptor) and orphan descriptors (without folder), logging at warn level.
     */
    public List<RootDirHandle> listRootDirs(String tenantId, String projectId) {
        requireTenant(tenantId);
        requireProject(projectId);
        Path root = projectFolder(tenantId, projectId);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(DESCRIPTOR_SUFFIX))
                    .filter(p -> !p.getFileName().toString().equals(WORKSPACE_FILE))
                    .map(p -> loadHandleFromDescriptor(tenantId, p))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(RootDirHandle::getDirName))
                    .toList();
        } catch (IOException e) {
            throw new RootDirException(
                    "Cannot list RootDirs for " + tenantId + "/" + projectId + ": " + e.getMessage(), e);
        }
    }

    /** Distinct handler types present in a project's workspace; empty if the folder is missing. */
    public Set<String> getRootDirTypes(String tenantId, String projectId) {
        return listRootDirs(tenantId, projectId).stream()
                .map(RootDirHandle::getType)
                .filter(t -> !isBlank(t))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Looks up a single RootDir by name. */
    public Optional<RootDirHandle> getRootDir(String tenantId, String projectId, String dirName) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (isBlank(dirName)) {
            throw new RootDirException("dirName is required");
        }
        Path root = projectFolder(tenantId, projectId);
        Path descriptorFile = root.resolve(dirName + DESCRIPTOR_SUFFIX);
        if (!Files.exists(descriptorFile)) {
            return Optional.empty();
        }
        return Optional.ofNullable(loadHandleFromDescriptor(tenantId, descriptorFile));
    }

    /** Persists the handle's descriptor again — after a handler changed it outside create/recover. */
    public void saveDescriptor(RootDirHandle handle) {
        rewriteDescriptor(
                handle.getPath().getParent().resolve(handle.getDirName() + DESCRIPTOR_SUFFIX), handle.getDescriptor());
    }

    // ---------------------------------------------------------------------
    // Working-RootDir tracking (per creator)
    // ---------------------------------------------------------------------

    /**
     * Registers a RootDir as the current "working" RootDir for a creator.
     * Subsequent default-resolution by tools picks it up before falling back to the
     * temp RootDir. Cleared explicitly via {@link #clearWorkingDir} or when the
     * RootDir or project goes away.
     */
    public void setWorkingDir(String tenantId, String projectId, String creatorProcessId, String dirName) {
        requireTenant(tenantId);
        requireProject(projectId);
        if (isBlank(creatorProcessId)) {
            throw new RootDirException("creatorProcessId is required");
        }
        if (isBlank(dirName)) {
            throw new RootDirException("dirName is required");
        }
        if (getRootDir(tenantId, projectId, dirName).isEmpty()) {
            throw new RootDirException(
                    "Cannot set working RootDir to non-existent " + tenantId + "/" + projectId + "/" + dirName);
        }
        workingDirByCreator.put(creatorCacheKey(tenantId, projectId, creatorProcessId), dirName);
    }

    /** The working RootDir name for a creator, if set. */
    public Optional<String> getWorkingDir(String tenantId, String projectId, String creatorProcessId) {
        if (isBlank(tenantId) || isBlank(projectId) || isBlank(creatorProcessId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(workingDirByCreator.get(creatorCacheKey(tenantId, projectId, creatorProcessId)));
    }

    /** Clears the working RootDir mapping for a creator. */
    public void clearWorkingDir(String tenantId, String projectId, String creatorProcessId) {
        if (isBlank(tenantId) || isBlank(projectId) || isBlank(creatorProcessId)) {
            return;
        }
        workingDirByCreator.remove(creatorCacheKey(tenantId, projectId, creatorProcessId));
    }

    // ---------------------------------------------------------------------
    // Temp convenience
    // ---------------------------------------------------------------------

    /**
     * The lazy temp RootDir for a creator, created on first call. Later calls with
     * the same {@code (tenantId, projectId, creatorProcessId)} triple reuse it. It
     * has {@code deleteOnCreatorClose=true} — call {@link #disposeByCreator} on
     * creator-close to clean up.
     */
    public RootDirHandle getOrCreateTempRootDir(String tenantId, String projectId, String creatorProcessId) {
        ensureTempRootDir(tenantId, projectId, creatorProcessId);
        String dirName = tempDirCache.get(creatorCacheKey(tenantId, projectId, creatorProcessId));
        if (dirName == null) {
            throw new RootDirException("Temp RootDir cache missing after ensure for " + tenantId + "/" + projectId + "/"
                    + creatorProcessId);
        }
        return getRootDir(tenantId, projectId, dirName)
                .orElseThrow(() -> new RootDirException(
                        "Temp RootDir vanished for " + tenantId + "/" + projectId + "/" + creatorProcessId));
    }

    /**
     * Creates a temp file inside a per-creator temp RootDir. The RootDir is created
     * lazily and removed when the creator is disposed via {@link #disposeByCreator}.
     */
    public Path createTempFile(
            String tenantId,
            String projectId,
            String creatorProcessId,
            @Nullable String prefix,
            @Nullable String suffix) {
        Path tempRoot = ensureTempRootDir(tenantId, projectId, creatorProcessId);
        checkDiskPressure(tenantId, projectId, "createTempFile");
        try {
            return Files.createTempFile(tempRoot, prefix == null ? "tmp" : prefix, suffix == null ? ".tmp" : suffix);
        } catch (IOException e) {
            throw new RootDirException("createTempFile failed: " + e.getMessage(), e);
        }
    }

    /** Like {@link #createTempFile} but returns a directory. */
    public Path createTempDirectory(
            String tenantId, String projectId, String creatorProcessId, @Nullable String prefix) {
        Path tempRoot = ensureTempRootDir(tenantId, projectId, creatorProcessId);
        checkDiskPressure(tenantId, projectId, "createTempDirectory");
        try {
            return Files.createTempDirectory(tempRoot, prefix == null ? "tmp" : prefix);
        } catch (IOException e) {
            throw new RootDirException("createTempDirectory failed: " + e.getMessage(), e);
        }
    }

    private Path ensureTempRootDir(String tenantId, String projectId, String creatorProcessId) {
        if (isBlank(creatorProcessId)) {
            throw new RootDirException("creatorProcessId is required");
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

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private Workspace ensureWorkspaceFor(String tenantId, String projectId) {
        return get(tenantId, projectId).orElseGet(() -> init(tenantId, projectId));
    }

    private Path projectFolder(String tenantId, String projectId) {
        return settings.get()
                .root()
                .toAbsolutePath()
                .normalize()
                .resolve(tenantId)
                .resolve(projectId)
                .normalize();
    }

    private void forgetCreatorCaches(String tenantId, String projectId) {
        String prefix = creatorCachePrefix(tenantId, projectId);
        tempDirCache.keySet().removeIf(k -> k.startsWith(prefix));
        workingDirByCreator.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String creatorCacheKey(String tenantId, String projectId, String creatorProcessId) {
        return tenantId + "/" + projectId + ":" + creatorProcessId;
    }

    private static String creatorCachePrefix(String tenantId, String projectId) {
        return tenantId + "/" + projectId + ":";
    }

    /**
     * Checks the filesystem holding the workspace root against the soft and hard
     * limits. Logs a warning at soft, throws at hard. Quiet on filesystems that
     * don't report sizes (sandboxed envs, network mounts).
     */
    private void checkDiskPressure(String tenantId, String projectId, String op) {
        Settings current = settings.get();
        int hard = current.hardLimitPercent();
        int soft = current.softLimitPercent();
        if (hard <= 0 && soft <= 0) {
            return;
        }
        Path existing = current.root().toAbsolutePath().normalize();
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
            throw new RootDirQuotaExceededException(op + " refused: workspace volume " + usedPercent
                    + "% used (hard limit " + hard + "%, tenantId=" + tenantId + ", projectId=" + projectId + ")");
        }
        if (soft > 0 && usedPercent >= soft) {
            log.warn(
                    "Workspace volume at {}% used (soft limit {}%) — op={} tenantId={} projectId={} root={}",
                    usedPercent, soft, op, tenantId, projectId, existing);
        }
    }

    private static Path requireRoot(Workspace ws) {
        Path root = ws.getRoot();
        if (root == null) {
            throw new RootDirException("Workspace has no resolved root path");
        }
        return root;
    }

    private String resolveDirName(Path workspaceRoot, @Nullable String labelHint) {
        if (isBlank(labelHint)) {
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
        Files.writeString(file, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** Overwrites an existing descriptor file. Used after a handler enriches it. */
    private void rewriteDescriptor(Path file, WorkspaceDescriptor descriptor) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(descriptor);
            Files.writeString(
                    file,
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to rewrite descriptor {}: {}", file, e.toString());
        }
    }

    private @Nullable RootDirHandle loadHandleFromDescriptor(String tenantId, Path descriptorFile) {
        WorkspaceDescriptor d;
        try {
            d = objectMapper.readValue(
                    Files.readString(descriptorFile, StandardCharsets.UTF_8), WorkspaceDescriptor.class);
        } catch (IOException | JacksonException e) {
            // Jackson 3 reports malformed JSON unchecked; a broken descriptor is skipped, not fatal.
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
            Files.writeString(
                    file,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ws),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new RootDirException("Cannot write workspace meta: " + e.getMessage(), e);
        }
    }

    private Workspace readWorkspaceMeta(Path file) {
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Workspace.class);
        } catch (IOException | JacksonException e) {
            throw new RootDirException("Cannot read workspace meta: " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
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

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private static void requireTenant(String tenantId) {
        if (isBlank(tenantId)) {
            throw new RootDirException("tenantId is required");
        }
        requirePathSegment("tenantId", tenantId);
    }

    private static void requireProject(String projectId) {
        if (isBlank(projectId)) {
            throw new RootDirException("projectId is required");
        }
        requirePathSegment("projectId", projectId);
    }

    /** Both ids become directory names under the workspace root, so they must stay one path segment. */
    private static void requirePathSegment(String what, String value) {
        if (!SAFE_SEGMENT.matcher(value).matches()) {
            throw new RootDirException(what + " is not a plain name (letters, digits, '.', '_', '-'): " + value);
        }
    }
}
