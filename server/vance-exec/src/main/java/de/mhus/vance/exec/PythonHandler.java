package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Python-backed RootDir — local venv plus optional git-persisted sources.
 * On init the handler runs {@code python -m venv .venv} inside the
 * RootDir; sources are either cloned from {@code repoUrl} (delegated to
 * {@link GitHandler}) or initialized as an empty local repo. The
 * descriptor stores the {@code pythonPath} actually used. The {@code .venv}
 * directory is excluded by a default {@code .gitignore} written at init.
 *
 * <p>Metadata schema:
 * <ul>
 *   <li>{@code pythonPath} — interpreter used at last venv build (informational, default {@code python3})</li>
 *   <li>{@code packages} — list of pip requirement specs (e.g. {@code numpy},
 *       {@code pandas==2.0}) installed into the venv at init. Declarative
 *       alternative to shipping a {@code requirements.txt} — the file arrives
 *       via a later import, so it is not present at provision time. Installed
 *       once on create/clear; a reused workspace keeps them.</li>
 *   <li>{@code repoUrl}, {@code branch}, {@code commit}, {@code public}
 *       — same keys as {@link GitHandler}</li>
 * </ul>
 */
@Slf4j
public class PythonHandler implements WorkspaceContentHandler {

    public static final String TYPE = "python";

    /** Default label for the canonical per-project Python workspace,
     *  mirroring {@link NodeHandler#DEFAULT_LABEL}. Brain-side Python
     *  tools provision it on demand so a model never has to name a
     *  matching RootDir — it just runs the script. Underscore-prefix
     *  marks the folder as system-managed, so user-facing tooling won't
     *  list it alongside user content. */
    public static final String DEFAULT_LABEL = "_python";

    public static final String META_PYTHON_PATH = "pythonPath";
    public static final String META_PACKAGES = "packages";
    public static final String DEFAULT_PYTHON_PATH = "python3";
    public static final String VENV_DIR = ".venv";
    public static final String REQUIREMENTS_FILE = "requirements.txt";

    private static final String DEFAULT_GITIGNORE = ".venv/\n__pycache__/\n*.pyc\n*.pyo\n.pytest_cache/\n";
    private static final long VENV_BUILD_TIMEOUT_SECONDS = 120;
    private static final long PIP_INSTALL_TIMEOUT_SECONDS = 600;

    /**
     * Wheels only: a source distribution runs its own build code at install time
     * ({@code setup.py}), which {@code --ignore-scripts} cannot stop for pip
     * ({@code planning/multi-tenant-isolation.md} P8).
     */
    private static final String ONLY_BINARY = "--only-binary=:all:";

    private final GitHandler gitHandler;
    private final GitOperations git;
    /** Provided by the brain; absent in the admin shell (see {@link NodeHandler}). */
    private final Supplier<@Nullable ProcessLauncher> launcherSupplier;

    public PythonHandler(
            GitHandler gitHandler, GitOperations git, Supplier<@Nullable ProcessLauncher> launcherSupplier) {
        this.gitHandler = gitHandler;
        this.git = git;
        this.launcherSupplier = launcherSupplier;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(RootDirHandle handle, RootDirSpec spec) {
        Map<String, Object> meta = HandlerSupport.mutableMetadata(handle.getDescriptor());
        String pythonPath = HandlerSupport.stringOr(meta, META_PYTHON_PATH, DEFAULT_PYTHON_PATH);
        requireInterpreter(pythonPath);
        String repoUrl = HandlerSupport.stringOr(meta, GitHandler.META_REPO_URL, null);

        if (!repoUrl.isBlank()) {
            // Delegate clone + commit-pinning to GitHandler.
            gitHandler.init(handle, spec);
            meta = HandlerSupport.mutableMetadata(handle.getDescriptor());
        } else {
            // No remote yet — initialise an empty local repo so the worker
            // can later set origin.
            try {
                git.init(handle.getPath());
            } catch (GitOperationException e) {
                throw new RootDirException(
                        "git init failed for python RootDir " + handle.getDirName() + ": " + e.getMessage(), e);
            }
        }

        HandlerSupport.writeDefaultGitignore(handle.getPath(), DEFAULT_GITIGNORE);
        buildVenv(handle, pythonPath);

        // Auto-install dependencies if the clone shipped a
        // requirements.txt: "set up Python for this project" means
        // "make the dependencies available".
        // Skipped for non-repo inits (the file simply isn't there).
        Path requirements = handle.getPath().resolve(REQUIREMENTS_FILE);
        if (Files.isRegularFile(requirements)) {
            pipInstallRequirements(handle, pythonPath);
        }

        // Declarative package list from workspace options — installed once at
        // init (the reused-workspace path skips init and keeps them).
        List<String> packages = packageList(meta, META_PACKAGES);
        if (!packages.isEmpty()) {
            pipInstallPackages(handle, pythonPath, packages);
        }

        meta.put(META_PYTHON_PATH, pythonPath);
        handle.getDescriptor().setMetadata(meta);
        log.info(
                "python init: {} (pythonPath={}, repoUrl={}, requirements={}, packages={})",
                handle.getDirName(),
                pythonPath,
                repoUrl == null ? "none" : repoUrl,
                Files.isRegularFile(requirements) ? "installed" : "absent",
                packages.size());
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
     * (typically via {@code WorkspaceService#rebuildPythonVenv}).
     */
    public void rebuildVenv(RootDirHandle handle, String pythonPath) {
        requireInterpreter(pythonPath);
        Path rootDirPath = handle.getPath();
        Path venv = rootDirPath.resolve(VENV_DIR);
        deleteRecursively(venv);
        buildVenv(handle, pythonPath);
        Path requirements = rootDirPath.resolve(REQUIREMENTS_FILE);
        if (Files.isRegularFile(requirements)) {
            pipInstallRequirements(handle, pythonPath);
        }
        Map<String, Object> meta = HandlerSupport.mutableMetadata(handle.getDescriptor());
        List<String> packages = packageList(meta, META_PACKAGES);
        if (!packages.isEmpty()) {
            pipInstallPackages(handle, pythonPath, packages);
        }
        meta.put(META_PYTHON_PATH, pythonPath);
        handle.getDescriptor().setMetadata(meta);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private static final java.util.regex.Pattern INTERPRETER_NAME =
            java.util.regex.Pattern.compile("python[0-9]*(\\.[0-9]+)*");

    /** Directories the agent cannot write to, where a system interpreter may live. */
    private static final List<String> SYSTEM_PREFIXES = List.of("/usr/", "/opt/", "/bin/");

    /**
     * The interpreter is started as a program, outside any command isolation, so it must be one the
     * system provides: a bare {@code python3.12} found on PATH, or an absolute path of that name
     * under a system directory. A path into a RootDir would run whatever the agent put there.
     */
    static void requireInterpreter(String pythonPath) {
        String name = pythonPath.substring(pythonPath.lastIndexOf('/') + 1);
        boolean bare = pythonPath.indexOf('/') < 0;
        boolean system =
                !bare && !pythonPath.contains("..") && SYSTEM_PREFIXES.stream().anyMatch(pythonPath::startsWith);
        if (!INTERPRETER_NAME.matcher(name).matches() || !(bare || system)) {
            throw new RootDirException("pythonPath '" + pythonPath
                    + "' is not a system interpreter — use e.g. 'python3' or '/usr/bin/python3.12'");
        }
    }

    private void buildVenv(RootDirHandle handle, String pythonPath) {
        runProcess(
                handle,
                new String[] {pythonPath, "-m", "venv", VENV_DIR},
                VENV_BUILD_TIMEOUT_SECONDS,
                "venv build (" + pythonPath + ")",
                false);
    }

    private void pipInstallRequirements(RootDirHandle handle, String pythonPath) {
        Path venvPython = handle.getPath().resolve(VENV_DIR).resolve("bin").resolve("python");
        String pythonBinary = Files.isExecutable(venvPython) ? venvPython.toString() : pythonPath;
        runProcess(
                handle,
                new String[] {pythonBinary, "-m", "pip", "install", ONLY_BINARY, "-r", REQUIREMENTS_FILE},
                PIP_INSTALL_TIMEOUT_SECONDS,
                "pip install -r " + REQUIREMENTS_FILE,
                true);
    }

    private void pipInstallPackages(RootDirHandle handle, String pythonPath, List<String> packages) {
        Path venvPython = handle.getPath().resolve(VENV_DIR).resolve("bin").resolve("python");
        String pythonBinary = Files.isExecutable(venvPython) ? venvPython.toString() : pythonPath;
        List<String> command = new ArrayList<>(List.of(pythonBinary, "-m", "pip", "install", ONLY_BINARY, "--"));
        command.addAll(packages);
        runProcess(
                handle,
                command.toArray(String[]::new),
                PIP_INSTALL_TIMEOUT_SECONDS,
                "pip install " + String.join(" ", packages),
                true);
    }

    /**
     * Read a package list from metadata. Each entry is a plain requirement
     * spec passed straight to the installer as a separate argv token (no
     * shell). A leading {@code -} is rejected so a list entry cannot smuggle
     * an installer flag (e.g. {@code --index-url} or {@code -r}).
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
            if (s.startsWith("-")) {
                throw new RootDirException("invalid package spec '" + s + "' in workspace options." + key
                        + " — must not start with '-' (option injection)");
            }
            out.add(s);
        }
        return out;
    }

    /** Runs the command in the RootDir through the {@link ProcessLauncher}, scoped to its project. */
    private void runProcess(
            RootDirHandle handle, String[] command, long timeoutSeconds, String label, boolean network) {
        HandlerSupport.runProcess(launcherSupplier, handle, command, timeoutSeconds, label, network);
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RootDirException("Cannot delete " + p + ": " + e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            throw new RootDirException("Cannot traverse " + dir + ": " + e.getMessage(), e);
        }
    }
}
