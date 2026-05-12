package de.mhus.vance.shared.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * Python-backed RootDir — local venv plus optional git-persisted sources.
 * On init the handler runs {@code python -m venv .venv} inside the
 * RootDir; sources are either cloned from {@code repoUrl} (delegated to
 * {@link GitHandler}) or initialized as an empty local repo. The
 * descriptor stores the {@code pythonPath} actually used; recover on
 * another pod uses that pod's local {@code python3} instead, since
 * binaries don't survive a migration.
 *
 * <p>Suspend persists sources via {@link GitHandler} (commit + push on
 * a {@code vance/suspend/<dirName>} branch); the {@code .venv}
 * directory is excluded by a default {@code .gitignore} written at
 * init. Recover re-clones, rebuilds the venv with the local
 * interpreter, and runs {@code pip install -r requirements.txt} if a
 * lockfile is present.
 *
 * <p>Metadata schema:
 * <ul>
 *   <li>{@code pythonPath} — interpreter used at last venv build (informational, default {@code python3})</li>
 *   <li>{@code repoUrl}, {@code branch}, {@code commit}, {@code credentialAlias},
 *       {@code suspendBranch}, {@code suspendCommit} — same keys as {@link GitHandler}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PythonHandler implements WorkspaceContentHandler {

    public static final String TYPE = "python";

    public static final String META_PYTHON_PATH = "pythonPath";
    public static final String DEFAULT_PYTHON_PATH = "python3";
    public static final String VENV_DIR = ".venv";
    public static final String REQUIREMENTS_FILE = "requirements.txt";

    private static final String DEFAULT_GITIGNORE =
            ".venv/\n__pycache__/\n*.pyc\n*.pyo\n.pytest_cache/\n";
    private static final long VENV_BUILD_TIMEOUT_SECONDS = 120;
    private static final long PIP_INSTALL_TIMEOUT_SECONDS = 600;

    private final GitHandler gitHandler;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(RootDirHandle handle, RootDirSpec spec) {
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        String pythonPath = stringOr(meta, META_PYTHON_PATH, DEFAULT_PYTHON_PATH);
        String repoUrl = stringOr(meta, GitHandler.META_REPO_URL, null);

        if (StringUtils.isNotBlank(repoUrl)) {
            // Delegate clone + commit-pinning to GitHandler.
            gitHandler.init(handle, spec);
            meta = mutableMetadata(handle.getDescriptor());
        } else {
            // No remote yet — initialise an empty local repo so the worker
            // can later set origin and call suspend.
            try {
                Git.init().setDirectory(handle.getPath().toFile()).call().close();
            } catch (GitAPIException e) {
                throw new WorkspaceException(
                        "git init failed for python RootDir " + handle.getDirName()
                                + ": " + e.getMessage(), e);
            }
        }

        writeDefaultGitignore(handle.getPath());
        buildVenv(handle.getPath(), pythonPath);

        // Auto-install dependencies if the clone shipped a
        // requirements.txt. Mirrors the recover-path: "set up Python
        // for this project" means "make the dependencies available".
        // Skipped for non-repo inits (the file simply isn't there).
        Path requirements = handle.getPath().resolve(REQUIREMENTS_FILE);
        if (Files.isRegularFile(requirements)) {
            pipInstallRequirements(handle.getPath(), pythonPath);
        }

        meta.put(META_PYTHON_PATH, pythonPath);
        handle.getDescriptor().setMetadata(meta);
        log.info("python init: {} (pythonPath={}, repoUrl={}, requirements={})",
                handle.getDirName(), pythonPath,
                repoUrl == null ? "none" : repoUrl,
                Files.isRegularFile(requirements) ? "installed" : "absent");
    }

    @Override
    public void suspend(RootDirHandle handle) {
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        String repoUrl = stringOr(meta, GitHandler.META_REPO_URL, null);
        if (StringUtils.isBlank(repoUrl)) {
            throw new WorkspaceException(
                    "python suspend requires a configured git remote on "
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
        // Use the recover pod's local interpreter, not the one from the descriptor —
        // the binary on disk is what counts, the descriptor field is informational.
        String pythonPath = DEFAULT_PYTHON_PATH;
        buildVenv(handle.getPath(), pythonPath);
        meta.put(META_PYTHON_PATH, pythonPath);
        descriptor.setMetadata(meta);
        handle.getDescriptor().setMetadata(meta);

        Path requirements = handle.getPath().resolve(REQUIREMENTS_FILE);
        if (Files.isRegularFile(requirements)) {
            pipInstallRequirements(handle.getPath(), pythonPath);
        }
        log.info("python recover: {} (pythonPath={})", handle.getDirName(), pythonPath);
    }

    @Override
    public void close(RootDirHandle handle) {
        // Folder + .venv removal handled by service.
    }

    /**
     * Rebuild the venv with a (possibly new) interpreter. Wipes
     * {@code .venv}, runs {@code <pythonPath> -m venv .venv}, and
     * reinstalls from {@code requirements.txt} if present. Sources
     * are untouched. Updates the handle's descriptor metadata with
     * the new {@code pythonPath}; the caller persists the descriptor
     * (typically via {@link WorkspaceService#rebuildPythonVenv}).
     */
    public void rebuildVenv(RootDirHandle handle, String pythonPath) {
        Path rootDirPath = handle.getPath();
        Path venv = rootDirPath.resolve(VENV_DIR);
        deleteRecursively(venv);
        buildVenv(rootDirPath, pythonPath);
        Path requirements = rootDirPath.resolve(REQUIREMENTS_FILE);
        if (Files.isRegularFile(requirements)) {
            pipInstallRequirements(rootDirPath, pythonPath);
        }
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        meta.put(META_PYTHON_PATH, pythonPath);
        handle.getDescriptor().setMetadata(meta);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void writeDefaultGitignore(Path rootDirPath) {
        Path gitignore = rootDirPath.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            return;
        }
        try {
            Files.writeString(gitignore, DEFAULT_GITIGNORE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.warn("Failed to write default .gitignore in {}: {}", rootDirPath, e.toString());
        }
    }

    private void buildVenv(Path rootDirPath, String pythonPath) {
        runProcess(rootDirPath,
                new String[]{pythonPath, "-m", "venv", VENV_DIR},
                VENV_BUILD_TIMEOUT_SECONDS,
                "venv build (" + pythonPath + ")");
    }

    private void pipInstallRequirements(Path rootDirPath, String pythonPath) {
        Path venvPython = rootDirPath.resolve(VENV_DIR).resolve("bin").resolve("python");
        String pythonBinary = Files.isExecutable(venvPython)
                ? venvPython.toString()
                : pythonPath;
        runProcess(rootDirPath,
                new String[]{pythonBinary, "-m", "pip", "install", "-r", REQUIREMENTS_FILE},
                PIP_INSTALL_TIMEOUT_SECONDS,
                "pip install -r " + REQUIREMENTS_FILE);
    }

    private static void runProcess(Path cwd, String[] command, long timeoutSeconds, String label) {
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

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new WorkspaceException(
                            "Cannot delete " + p + ": " + e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            throw new WorkspaceException(
                    "Cannot traverse " + dir + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> mutableMetadata(WorkspaceDescriptor d) {
        Map<String, Object> meta = d.getMetadata();
        if (meta == null) {
            return new LinkedHashMap<>();
        }
        if (meta instanceof HashMap<?, ?> || meta instanceof LinkedHashMap<?, ?>) {
            return meta;
        }
        return new LinkedHashMap<>(meta);
    }

    @SuppressWarnings("SameParameterValue")
    private static String stringOr(Map<String, Object> meta, String key, @Nullable String fallback) {
        Object v = meta.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback == null ? "" : fallback;
    }
}
