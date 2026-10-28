package de.mhus.vance.runner;

import de.mhus.vance.exec.ExecEnvPolicy;
import de.mhus.vance.exec.ProcessIsolation;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.ProcessSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Starts the programs the runner itself runs for a RootDir — {@code git}, {@code npm},
 * {@code pip}, later {@code ffmpeg} and friends — as opposed to the commands an agent
 * submits through {@code exec.start}. One place decides what such a child sees: the
 * sealed base environment, a {@code HOME} of its own, and, when the operator configured
 * a process wrapper, only the paths its spec declares.
 *
 * <p>The runner's own environment ({@code VANCE_RUNNER_*}) never reaches a child; the
 * {@link ExecEnvPolicy} base is all it inherits, plus what the spec adds on top.
 */
final class RunnerProcessLauncher implements ProcessLauncher {

    private static final String UNSCOPED_HOME = "process-home";

    private final Path dataDir;
    private final ProcessIsolation.Wrapper wrapper;
    private final ProcessIsolation.Limits limits;

    /**
     * @param processWrapper the wrapper template ({@code {home}}, {@code {workdir}},
     *     {@code {binds}}, {@code {ro-binds}}, {@code {net}} — see {@link ProcessIsolation}),
     *     or {@code null} to start children unwrapped
     */
    RunnerProcessLauncher(Path dataDir, @Nullable String processWrapper) {
        this.dataDir = dataDir;
        this.wrapper = ProcessIsolation.Wrapper.bubblewrap(
                processWrapper == null || processWrapper.isBlank() ? null : processWrapper.trim());
        this.limits = ProcessIsolation.Limits.NONE;
    }

    @Override
    public Process start(ProcessSpec spec) throws IOException {
        Map<String, String> childEnv = childEnvironment(spec);
        Path home = Path.of(childEnv.get("HOME"));
        Files.createDirectories(home);
        Path workdir = spec.workingDir() != null ? spec.workingDir() : home;
        List<String> argv = ProcessIsolation.argv(spec, home, workdir, wrapper, limits);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(spec.redirectErrorStream());
        if (spec.workingDir() != null) {
            pb.directory(spec.workingDir().toFile());
        }
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(childEnv);
        return pb.start();
    }

    /** The sealed base, the scope's {@code HOME}, then what the spec adds. */
    private Map<String, String> childEnvironment(ProcessSpec spec) {
        Map<String, String> env = ExecEnvPolicy.seal(System.getenv(), List.of());
        ProcessSpec.Scope scope = spec.scope();
        Path home;
        if (scope != null) {
            home = dataDir.resolve("homes").resolve(scope.tenantId()).resolve(scope.projectId());
        } else if (spec.workingDir() != null) {
            home = spec.workingDir();
        } else {
            home = dataDir.resolve(UNSCOPED_HOME);
        }
        env.put("HOME", home.toString());
        env.putAll(spec.environment());
        return env;
    }
}
