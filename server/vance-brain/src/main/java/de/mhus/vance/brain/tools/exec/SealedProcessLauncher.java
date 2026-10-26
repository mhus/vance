package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.shared.process.ProcessLauncher;
import de.mhus.vance.shared.process.ProcessSpec;
import java.io.IOException;
import java.nio.file.Path;
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
 * scope, otherwise the working directory, otherwise the JVM temp directory.
 */
@Service
public class SealedProcessLauncher implements ProcessLauncher {

    private final ExecEnvironmentBuilder environment;

    public SealedProcessLauncher(ExecEnvironmentBuilder environment) {
        this.environment = environment;
    }

    @Override
    public Process start(ProcessSpec spec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spec.command()).redirectErrorStream(spec.redirectErrorStream());
        if (spec.workingDir() != null) {
            pb.directory(spec.workingDir().toFile());
        }
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(childEnvironment(spec));
        return pb.start();
    }

    Map<String, String> childEnvironment(ProcessSpec spec) {
        ProcessSpec.Scope scope = spec.scope();
        if (scope != null) {
            return environment.build(scope.tenantId(), scope.projectId(), scope.userId(), spec.environment());
        }
        Path home = spec.workingDir() != null ? spec.workingDir() : Path.of(System.getProperty("java.io.tmpdir"));
        return environment.buildUnscoped(home, spec.environment());
    }
}
