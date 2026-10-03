package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.toolpack.exec.ExecEnvPolicy;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Builds the subprocess environment for every exec job. Every command a
 * {@code work_exec_run} / {@code python_run} job spawns runs with a sealed
 * environment — the non-secret base from {@link ExecEnvPolicy}, the operator's
 * {@code vance.exec.env.allow} pass-throughs, then the caller's extras on top.
 *
 * <p>The point is the direction of the default: the Brain process holds the
 * Mongo password, the encryption password, provider API keys and the internal
 * token, and an agent-driven shell command must never see them. Before this
 * sealing, {@code ExecManager} left {@code ProcessBuilder.environment()}
 * untouched, so the subprocess inherited all of it — the finding behind
 * GitHub issue {@code mhus/vance#62}.
 */
@Service
public class ExecEnvironmentBuilder {

    private final ExecProperties properties;

    public ExecEnvironmentBuilder(ExecProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the complete child environment for one job: sealed base, then
     * {@code extras} (script-run tokens and the like) which may override base
     * values — a script path that pins its own {@code PATH} or {@code HOME}
     * wins over the inherited one. {@code null} extras mean base only.
     */
    public Map<String, String> build(@Nullable Map<String, String> extras) {
        Map<String, String> env =
                ExecEnvPolicy.seal(System.getenv(), properties.getEnv().getAllow());
        if (extras != null) {
            env.putAll(extras);
        }
        return env;
    }
}
