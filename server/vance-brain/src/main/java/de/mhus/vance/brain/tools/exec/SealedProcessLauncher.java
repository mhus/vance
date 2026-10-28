package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.shared.process.ProcessLauncher;
import de.mhus.vance.shared.process.ProcessSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The brain's {@link ProcessLauncher}: every starter outside {@code ExecManager}
 * (npm, pip, ffmpeg, whisper, yt-dlp, latexmk, Rserve) gets the same sealed child
 * environment as an exec job — the non-secret base, the operator's
 * {@code vance.exec.env.allow} pass-throughs, a {@code HOME} of its own — instead
 * of inheriting the brain's credentials ({@code planning/multi-tenant-isolation.md}
 * P6, phase 2a).
 *
 * <p>{@code HOME} is the scope's home ({@code HomesService}) when the spec names a
 * scope, otherwise the working directory, otherwise {@value #UNSCOPED_HOME} under
 * the JVM temp directory — never the temp directory itself, which a wrapped child
 * would then see whole.
 *
 * <p>With {@code vance.exec.isolation.mode=custom} and a
 * {@code process-wrapper} the argv runs inside that wrapper, seeing only what the
 * spec declares; {@code vance.exec.process-limits.*} caps it with {@code prlimit}
 * (phase 2c, see {@link ProcessIsolation}).
 */
@Service
public class SealedProcessLauncher implements ProcessLauncher {

    static final String UNSCOPED_HOME = "vance-process-home";

    private final ExecEnvironmentBuilder environment;
    private final ExecProperties properties;

    public SealedProcessLauncher(ExecEnvironmentBuilder environment, ExecProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public Process start(ProcessSpec spec) throws IOException {
        Map<String, String> childEnv = childEnvironment(spec);
        Files.createDirectories(home(childEnv));
        ProcessBuilder pb = new ProcessBuilder(argv(spec, childEnv)).redirectErrorStream(spec.redirectErrorStream());
        if (spec.workingDir() != null) {
            pb.directory(spec.workingDir().toFile());
        }
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(childEnv);
        return pb.start();
    }

    /** Whether starters run inside the configured process wrapper. */
    public boolean isolated() {
        return ProcessIsolation.enabled(properties.getIsolation());
    }

    List<String> argv(ProcessSpec spec, Map<String, String> childEnv) {
        Path home = home(childEnv);
        Path workdir = spec.workingDir() != null ? spec.workingDir() : home;
        return ProcessIsolation.argv(spec, home, workdir, properties.getIsolation(), properties.getProcessLimits());
    }

    Map<String, String> childEnvironment(ProcessSpec spec) {
        ProcessSpec.Scope scope = spec.scope();
        if (scope != null) {
            return environment.build(scope.tenantId(), scope.projectId(), scope.userId(), spec.environment());
        }
        Path home = spec.workingDir() != null
                ? spec.workingDir()
                : Path.of(System.getProperty("java.io.tmpdir"), UNSCOPED_HOME);
        return environment.buildUnscoped(home, spec.environment());
    }

    private static Path home(Map<String, String> childEnv) {
        return Path.of(childEnv.getOrDefault("HOME", System.getProperty("java.io.tmpdir")));
    }
}
