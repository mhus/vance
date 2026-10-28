package de.mhus.vance.shared.workspace;

import de.mhus.vance.shared.process.ProcessLauncher;
import de.mhus.vance.shared.process.ProcessSpec;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Node-backed RootDir — local {@code node_modules} plus optional
 * git-persisted {@code package.json} + {@code package-lock.json}.
 * On init the handler runs {@code npm init -y} (or restores from a
 * git clone) and an {@code npm install --ignore-scripts} pass when a
 * lockfile is present. Sources persist via {@link GitHandler}; the
 * {@code node_modules} directory is excluded by a default {@code
 * .gitignore} written at init.
 *
 * <p>Sister to {@link PythonHandler}. Same shape, same idempotency
 * rules. Hactar-generated scripts that {@code require()}
 * external libraries point at one of these via the
 * {@code @workspaceRoot} script-header tag (see
 * {@code specification/script-engine.md} §3.6).
 *
 * <h2>Security notes</h2>
 *
 * <p>{@code npm install} is invoked with {@code --ignore-scripts} so
 * a malicious package's {@code postinstall} hook cannot execute
 * arbitrary code at install time. The brain only ever <em>reads</em>
 * from {@code node_modules}; script execution happens inside the
 * GraalJS sandbox, which uses a scoped {@code FileSystem} that only
 * permits reads under {@code <rootDir>/node_modules/} (see
 * {@code GraaljsScriptExecutor}).
 *
 * <p>Every install path takes registry specs only ({@link NpmPackageSpec}),
 * places them after {@code --} so npm never reads one as an option, pins the
 * registry on the command line, and runs the operator-configured npm binary
 * ({@code vance.workspace.node.npm-path}) — never one named in RootDir
 * metadata. A single install that pushes {@code node_modules} past
 * {@code vance.workspace.node.max-bytes} is rolled back.
 *
 * <p>Metadata schema:
 * <ul>
 *   <li>{@code nodePath} — node binary (informational, default {@code node})</li>
 *   <li>{@code packages} — list of npm package specs (e.g. {@code lodash},
 *       {@code axios@1.6}) installed into {@code node_modules} at init.
 *       Declarative alternative to shipping a {@code package.json} +
 *       lockfile, which for a compose arrive via a later import. Installed
 *       once on create/clear; a reused workspace keeps them.</li>
 *   <li>{@code repoUrl}, {@code branch}, {@code commit}, {@code credentialAlias},
 *       {@code suspendBranch}, {@code suspendCommit} — same keys as {@link GitHandler}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NodeHandler implements WorkspaceContentHandler {

    public static final String TYPE = "node";

    public static final String META_PACKAGES = "packages";
    public static final String META_NODE_PATH = "nodePath";
    public static final String DEFAULT_NODE_PATH = "node";

    public static final String NODE_MODULES_DIR = "node_modules";
    public static final String PACKAGE_JSON = "package.json";
    public static final String PACKAGE_LOCK_JSON = "package-lock.json";

    /** Default label that Brain-side tools (and the {@code @workspaceRoot}
     *  header tag) pass to {@link WorkspaceService#createRootDir} when
     *  the caller wants the canonical per-project Node workspace.
     *  Underscore-prefix marks the folder as system-managed, so
     *  user-facing tooling won't list it alongside user content. */
    public static final String DEFAULT_LABEL = "_jsengine";

    private static final String DEFAULT_GITIGNORE = "node_modules/\n.npm/\n*.log\n";
    private static final long NPM_INIT_TIMEOUT_SECONDS = 60;
    private static final long NPM_INSTALL_TIMEOUT_SECONDS = 600;

    private final GitHandler gitHandler;
    private final WorkspaceProperties properties;
    /**
     * Provided by the brain. Absent in applications that never start npm (the
     * admin shell loads this handler through the shared package scan) — there a
     * start fails with a clear message instead of spawning an unsealed child.
     */
    private final ObjectProvider<ProcessLauncher> launcherProvider;

    /**
     * Boot-time presence check for npm. Logs warn (not error) if npm
     * isn't on PATH — script-engine's require pathway will fail-fast
     * with a clear message when it's actually used, and the handler
     * is still loadable by Spring (other code may instantiate it
     * without ever calling install).
     */
    @PostConstruct
    void verifyNpmAvailable() {
        ProcessLauncher launcher = launcherProvider.getIfAvailable();
        if (launcher == null) {
            log.debug("NodeHandler: no ProcessLauncher in this application — npm check skipped");
            return;
        }
        try {
            Process p = launcher.start(ProcessSpec.of(List.of(npmPath(), "--version"), null));
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("npm --version timed out — node workspaces will not work; "
                        + "install nodejs+npm on the brain pod");
                return;
            }
            if (p.exitValue() != 0) {
                log.warn("npm --version exited {} — node workspaces will not work: {}", p.exitValue(), output);
                return;
            }
            log.info("NodeHandler ready: npm {} on PATH", output);
        } catch (IOException e) {
            log.warn(
                    "npm not found on PATH ({}). Node workspaces and "
                            + "script-engine require() will not work. Install "
                            + "nodejs+npm on the brain pod (Dockerfile or host).",
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(RootDirHandle handle, RootDirSpec spec) {
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        String repoUrl = stringOr(meta, GitHandler.META_REPO_URL, null);

        if (StringUtils.isNotBlank(repoUrl)) {
            gitHandler.init(handle, spec);
            meta = mutableMetadata(handle.getDescriptor());
        } else {
            initGitRepository(handle);
        }

        writeDefaultGitignore(handle.getPath());

        // Initialise package.json if neither the clone nor a previous
        // run left one — npm install needs it to exist.
        Path packageJson = handle.getPath().resolve(PACKAGE_JSON);
        if (!Files.isRegularFile(packageJson)) {
            npmInit(handle);
        }

        // Restore node_modules from the lockfile when the clone shipped
        // one. Mirrors PythonHandler's "auto-install if requirements
        // are present" rule.
        Path lockfile = handle.getPath().resolve(PACKAGE_LOCK_JSON);
        if (Files.isRegularFile(lockfile)) {
            npmCi(handle);
        }

        // Declarative package list from workspace options — installed once at
        // init (the reused-workspace path skips init and keeps them).
        List<String> packages = packageList(meta, META_PACKAGES);
        if (!packages.isEmpty()) {
            npmInstallPackages(handle, packages);
            enforceSizeLimit(handle);
        }

        handle.getDescriptor().setMetadata(meta);
        log.info(
                "node init: {} (repoUrl={}, lockfile={}, packages={})",
                handle.getDirName(),
                repoUrl == null ? "none" : repoUrl,
                Files.isRegularFile(lockfile) ? "ci'd" : "absent",
                packages.size());
    }

    @Override
    public void suspend(RootDirHandle handle) {
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        String repoUrl = stringOr(meta, GitHandler.META_REPO_URL, null);
        if (StringUtils.isBlank(repoUrl)) {
            // No remote: like a temp RootDir, the content does not survive —
            // only the descriptor does, and recover rebuilds a fresh
            // environment from it. Must not block suspend/release
            // (planning/multi-tenant-isolation.md W5/E7).
            log.info("node suspend: {} has no git remote — its content is not kept", handle.getDirName());
            return;
        }
        gitHandler.suspend(handle);
    }

    @Override
    public void recover(RootDirHandle handle, WorkspaceDescriptor descriptor) {
        Map<String, Object> meta = mutableMetadata(descriptor);
        if (StringUtils.isBlank(stringOr(meta, GitHandler.META_REPO_URL, null))) {
            recoverFresh(handle, meta);
            return;
        }
        gitHandler.recover(handle, descriptor);
        Path lockfile = handle.getPath().resolve(PACKAGE_LOCK_JSON);
        if (Files.isRegularFile(lockfile)) {
            npmCi(handle);
        } else {
            // Pre-suspend dir might not have had a lockfile committed —
            // re-init package.json so install commands work.
            if (!Files.isRegularFile(handle.getPath().resolve(PACKAGE_JSON))) {
                npmInit(handle);
            }
        }
        log.info("node recover: {}", handle.getDirName());
    }

    /**
     * Recover without a git remote (E7): nothing was kept, so the RootDir comes
     * back empty with a fresh {@code package.json} and the declared
     * {@code packages} from the descriptor installed again.
     */
    private void recoverFresh(RootDirHandle handle, Map<String, Object> meta) {
        initGitRepository(handle);
        writeDefaultGitignore(handle.getPath());
        npmInit(handle);
        List<String> packages = packageList(meta, META_PACKAGES);
        if (!packages.isEmpty()) {
            npmInstallPackages(handle, packages);
            enforceSizeLimit(handle);
        }
        log.info(
                "node recover: {} rebuilt empty (no git remote), {} declared package(s) reinstalled",
                handle.getDirName(),
                packages.size());
    }

    private static void initGitRepository(RootDirHandle handle) {
        try {
            Git.init().setDirectory(handle.getPath().toFile()).call().close();
        } catch (GitAPIException e) {
            throw new WorkspaceException(
                    "git init failed for node RootDir " + handle.getDirName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close(RootDirHandle handle) {
        // Folder + node_modules removal handled by service.
    }

    // ──────────────────── public install hooks ────────────────────

    /**
     * Run {@code npm install --save --ignore-scripts -- <spec>}. Only registry
     * specs are accepted ({@link NpmPackageSpec}); an install that pushes
     * {@code node_modules} past {@code vance.workspace.node.max-bytes} is
     * uninstalled again and reported as {@link WorkspaceQuotaExceededException}.
     *
     * @throws WorkspaceException on an invalid spec or a failed npm run
     */
    public void install(RootDirHandle handle, String packageSpec) {
        NpmPackageSpec spec = NpmPackageSpec.parse(packageSpec);
        runProcess(
                handle,
                new String[] {
                    npmPath(),
                    "install",
                    "--save",
                    "--ignore-scripts",
                    "--no-audit",
                    "--no-fund",
                    registryOption(),
                    "--",
                    spec.toArgument()
                },
                NPM_INSTALL_TIMEOUT_SECONDS,
                "npm install " + spec.toArgument(),
                true);
        try {
            enforceSizeLimit(handle);
        } catch (WorkspaceQuotaExceededException e) {
            uninstall(handle, spec.name());
            throw e;
        }
    }

    /**
     * Run {@code npm uninstall --save -- <name>}.
     *
     * @throws WorkspaceException when {@code packageName} is not a plain registry name
     */
    public void uninstall(RootDirHandle handle, String packageName) {
        NpmPackageSpec spec = NpmPackageSpec.parse(packageName);
        if (spec.range() != null) {
            throw new WorkspaceException("npm uninstall takes a package name, not a version: '" + packageName + "'");
        }
        runProcess(
                handle,
                new String[] {npmPath(), "uninstall", "--save", "--no-audit", "--no-fund", "--", spec.name()},
                NPM_INSTALL_TIMEOUT_SECONDS,
                "npm uninstall " + spec.name(),
                false);
    }

    /** {@code true} when {@code node_modules/<name>/package.json} exists. */
    public static boolean isInstalled(Path rootDir, String packageName) {
        return Files.isRegularFile(
                rootDir.resolve(NODE_MODULES_DIR).resolve(packageName).resolve(PACKAGE_JSON));
    }

    private ProcessLauncher launcher() {
        ProcessLauncher launcher = launcherProvider.getIfAvailable();
        if (launcher == null) {
            throw new WorkspaceException("starting npm is not available in this application (no ProcessLauncher)");
        }
        return launcher;
    }

    private String npmPath() {
        return properties.getNode().getNpmPath();
    }

    private String registryOption() {
        return "--registry=" + properties.getNode().getRegistry();
    }

    private void enforceSizeLimit(RootDirHandle handle) {
        long max = properties.getNode().getMaxBytes();
        long used = treeSize(handle.getPath().resolve(NODE_MODULES_DIR));
        if (used > max) {
            throw new WorkspaceQuotaExceededException("node_modules of " + handle.getDirName() + " would hold " + used
                    + " bytes, above vance.workspace.node.max-bytes (" + max + ")");
        }
    }

    /** Sum of regular-file sizes below {@code dir}; symlinks are not followed. */
    private static long treeSize(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(NodeHandler::sizeOf)
                    .sum();
        } catch (IOException | UncheckedIOException e) {
            throw new WorkspaceException("cannot measure " + dir + ": " + e.getMessage(), e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ──────────────────── Internals ────────────────────

    private void writeDefaultGitignore(Path rootDirPath) {
        Path gitignore = rootDirPath.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        try {
            Files.writeString(gitignore, DEFAULT_GITIGNORE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.warn("Failed to write default .gitignore in {}: {}", rootDirPath, e.toString());
        }
    }

    private void npmInit(RootDirHandle handle) {
        runProcess(handle, new String[] {npmPath(), "init", "-y"}, NPM_INIT_TIMEOUT_SECONDS, "npm init -y", false);
    }

    private void npmCi(RootDirHandle handle) {
        runProcess(
                handle,
                new String[] {npmPath(), "ci", "--ignore-scripts", "--no-audit", "--no-fund", registryOption()},
                NPM_INSTALL_TIMEOUT_SECONDS,
                "npm ci",
                true);
    }

    private void npmInstallPackages(RootDirHandle handle, List<String> packages) {
        String[] fixed = {
            npmPath(), "install", "--save", "--ignore-scripts", "--no-audit", "--no-fund", registryOption(), "--"
        };
        String[] command = new String[fixed.length + packages.size()];
        System.arraycopy(fixed, 0, command, 0, fixed.length);
        for (int i = 0; i < packages.size(); i++) {
            command[fixed.length + i] = packages.get(i);
        }
        runProcess(handle, command, NPM_INSTALL_TIMEOUT_SECONDS, "npm install " + String.join(" ", packages), true);
    }

    /**
     * Read a package list from metadata. Each entry must be a registry spec
     * ({@link NpmPackageSpec}) and is passed to npm as a separate argv token
     * after {@code --} (no shell).
     */
    private static List<String> packageList(Map<String, Object> meta, String key) {
        Object raw = meta.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            String s = o.toString().trim();
            if (s.isEmpty()) {
                continue;
            }
            out.add(NpmPackageSpec.parse(s).toArgument());
        }
        return out;
    }

    /**
     * Runs npm in the RootDir through the {@link ProcessLauncher}, scoped to its
     * project. {@code network}: only installs reach the registry.
     */
    private void runProcess(
            RootDirHandle handle, String[] command, long timeoutSeconds, String label, boolean network) {
        Path cwd = handle.getPath();
        ProcessSpec spec = ProcessSpec.of(List.of(command), cwd).withScope(handle.getTenantId(), handle.getProjectId());
        if (network) {
            spec = spec.withNetwork();
        }
        Process p;
        try {
            p = launcher().start(spec);
        } catch (IOException e) {
            throw new WorkspaceException(label + " failed to start: " + e.getMessage(), e);
        }
        String output;
        try {
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new WorkspaceException(label + " timed out after " + timeoutSeconds + "s");
            }
        } catch (IOException e) {
            throw new WorkspaceException(label + " I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException(label + " interrupted", e);
        }
        int exit = p.exitValue();
        if (exit != 0) {
            throw new WorkspaceException(
                    label + " exited with code " + exit + (output.isBlank() ? "" : ": " + output.strip()));
        }
        log.debug("{} ok (cwd={})", label, cwd);
    }

    private static Map<String, Object> mutableMetadata(WorkspaceDescriptor d) {
        Map<String, Object> meta = d.getMetadata();
        if (meta == null) return new LinkedHashMap<>();
        if (meta instanceof HashMap<?, ?> || meta instanceof LinkedHashMap<?, ?>) {
            return meta;
        }
        return new LinkedHashMap<>(meta);
    }

    @SuppressWarnings("SameParameterValue")
    private static String stringOr(Map<String, Object> meta, String key, @Nullable String fallback) {
        Object v = meta.get(key);
        if (v instanceof String s && !s.isBlank()) return s;
        return fallback == null ? "" : fallback;
    }
}
