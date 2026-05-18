package de.mhus.vance.shared.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.Nullable;
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
 * rules. Deep Thought-generated scripts that {@code require()}
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
 * <p>Metadata schema:
 * <ul>
 *   <li>{@code npmPath} — npm binary used at last install (informational, default {@code npm})</li>
 *   <li>{@code nodePath} — node binary (informational, default {@code node})</li>
 *   <li>{@code repoUrl}, {@code branch}, {@code commit}, {@code credentialAlias},
 *       {@code suspendBranch}, {@code suspendCommit} — same keys as {@link GitHandler}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NodeHandler implements WorkspaceContentHandler {

    public static final String TYPE = "node";

    public static final String META_NPM_PATH = "npmPath";
    public static final String DEFAULT_NPM_PATH = "npm";
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

    private static final String DEFAULT_GITIGNORE =
            "node_modules/\n.npm/\n*.log\n";
    private static final long NPM_INIT_TIMEOUT_SECONDS = 60;
    private static final long NPM_INSTALL_TIMEOUT_SECONDS = 600;

    private final GitHandler gitHandler;

    /**
     * Boot-time presence check for npm. Logs warn (not error) if npm
     * isn't on PATH — script-engine's require pathway will fail-fast
     * with a clear message when it's actually used, and the handler
     * is still loadable by Spring (other code may instantiate it
     * without ever calling install).
     */
    @PostConstruct
    void verifyNpmAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(DEFAULT_NPM_PATH, "--version")
                    .redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("npm --version timed out — node workspaces will not work; "
                        + "install nodejs+npm on the brain pod");
                return;
            }
            if (p.exitValue() != 0) {
                log.warn("npm --version exited {} — node workspaces will not work: {}",
                        p.exitValue(), output);
                return;
            }
            log.info("NodeHandler ready: npm {} on PATH", output);
        } catch (IOException e) {
            log.warn("npm not found on PATH ({}). Node workspaces and "
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
        String npmPath = stringOr(meta, META_NPM_PATH, DEFAULT_NPM_PATH);
        String repoUrl = stringOr(meta, GitHandler.META_REPO_URL, null);

        if (StringUtils.isNotBlank(repoUrl)) {
            gitHandler.init(handle, spec);
            meta = mutableMetadata(handle.getDescriptor());
        } else {
            try {
                Git.init().setDirectory(handle.getPath().toFile()).call().close();
            } catch (GitAPIException e) {
                throw new WorkspaceException(
                        "git init failed for node RootDir " + handle.getDirName()
                                + ": " + e.getMessage(), e);
            }
        }

        writeDefaultGitignore(handle.getPath());

        // Initialise package.json if neither the clone nor a previous
        // run left one — npm install needs it to exist.
        Path packageJson = handle.getPath().resolve(PACKAGE_JSON);
        if (!Files.isRegularFile(packageJson)) {
            npmInit(handle.getPath(), npmPath);
        }

        // Restore node_modules from the lockfile when the clone shipped
        // one. Mirrors PythonHandler's "auto-install if requirements
        // are present" rule.
        Path lockfile = handle.getPath().resolve(PACKAGE_LOCK_JSON);
        if (Files.isRegularFile(lockfile)) {
            npmCi(handle.getPath(), npmPath);
        }

        meta.put(META_NPM_PATH, npmPath);
        handle.getDescriptor().setMetadata(meta);
        log.info("node init: {} (npmPath={}, repoUrl={}, lockfile={})",
                handle.getDirName(), npmPath,
                repoUrl == null ? "none" : repoUrl,
                Files.isRegularFile(lockfile) ? "ci'd" : "absent");
    }

    @Override
    public void suspend(RootDirHandle handle) {
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        String repoUrl = stringOr(meta, GitHandler.META_REPO_URL, null);
        if (StringUtils.isBlank(repoUrl)) {
            throw new WorkspaceException(
                    "node suspend requires a configured git remote on "
                            + handle.getDirName() + " — set metadata."
                            + GitHandler.META_REPO_URL + " or accept that this "
                            + "RootDir is not suspendable");
        }
        gitHandler.suspend(handle);
    }

    @Override
    public void recover(RootDirHandle handle, WorkspaceDescriptor descriptor) {
        gitHandler.recover(handle, descriptor);
        Map<String, Object> meta = mutableMetadata(descriptor);
        // Use the recover pod's local npm.
        String npmPath = DEFAULT_NPM_PATH;
        Path lockfile = handle.getPath().resolve(PACKAGE_LOCK_JSON);
        if (Files.isRegularFile(lockfile)) {
            npmCi(handle.getPath(), npmPath);
        } else {
            // Pre-suspend dir might not have had a lockfile committed —
            // re-init package.json so install commands work.
            if (!Files.isRegularFile(handle.getPath().resolve(PACKAGE_JSON))) {
                npmInit(handle.getPath(), npmPath);
            }
        }
        meta.put(META_NPM_PATH, npmPath);
        descriptor.setMetadata(meta);
        handle.getDescriptor().setMetadata(meta);
        log.info("node recover: {} (npmPath={})", handle.getDirName(), npmPath);
    }

    @Override
    public void close(RootDirHandle handle) {
        // Folder + node_modules removal handled by service.
    }

    // ──────────────────── public install hooks ────────────────────

    /** Run {@code npm install <package> --save --ignore-scripts}. */
    public void install(RootDirHandle handle, String packageSpec) {
        runProcess(handle.getPath(),
                new String[]{
                        npmPath(handle), "install", "--save",
                        "--ignore-scripts", "--no-audit", "--no-fund",
                        packageSpec},
                NPM_INSTALL_TIMEOUT_SECONDS,
                "npm install " + packageSpec);
    }

    /** Run {@code npm uninstall <name> --save}. */
    public void uninstall(RootDirHandle handle, String packageName) {
        runProcess(handle.getPath(),
                new String[]{
                        npmPath(handle), "uninstall", "--save",
                        "--no-audit", "--no-fund",
                        packageName},
                NPM_INSTALL_TIMEOUT_SECONDS,
                "npm uninstall " + packageName);
    }

    private String npmPath(RootDirHandle handle) {
        Map<String, Object> meta = handle.getDescriptor() == null
                ? Map.of() : (handle.getDescriptor().getMetadata() == null
                        ? Map.of() : handle.getDescriptor().getMetadata());
        Object raw = meta.get(META_NPM_PATH);
        return raw instanceof String s && !s.isBlank() ? s : DEFAULT_NPM_PATH;
    }

    // ──────────────────── Internals ────────────────────

    private void writeDefaultGitignore(Path rootDirPath) {
        Path gitignore = rootDirPath.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        try {
            Files.writeString(gitignore, DEFAULT_GITIGNORE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.warn("Failed to write default .gitignore in {}: {}",
                    rootDirPath, e.toString());
        }
    }

    private void npmInit(Path cwd, String npmPath) {
        runProcess(cwd,
                new String[]{npmPath, "init", "-y"},
                NPM_INIT_TIMEOUT_SECONDS,
                "npm init -y (" + npmPath + ")");
    }

    private void npmCi(Path cwd, String npmPath) {
        runProcess(cwd,
                new String[]{
                        npmPath, "ci",
                        "--ignore-scripts", "--no-audit", "--no-fund"},
                NPM_INSTALL_TIMEOUT_SECONDS,
                "npm ci (" + npmPath + ")");
    }

    private static void runProcess(Path cwd, String[] command,
            long timeoutSeconds, String label) {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new WorkspaceException(
                    label + " failed to start: " + e.getMessage(), e);
        }
        String output;
        try {
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new WorkspaceException(
                        label + " timed out after " + timeoutSeconds + "s");
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
                    label + " exited with code " + exit
                            + (output.isBlank() ? "" : ": " + output.strip()));
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
