package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.shared.homes.HomesService;
import de.mhus.vance.toolpack.exec.ExecEnvPolicy;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Builds the subprocess environment for every exec job. Every command a
 * {@code work_exec_run} / {@code python_run} job spawns runs with a sealed
 * environment — the non-secret base from {@link ExecEnvPolicy}, the operator's
 * {@code vance.exec.env.allow} pass-throughs, a scoped {@code HOME}, then the
 * caller's extras on top.
 *
 * <p>The point is the direction of the default: the Brain process holds the
 * Mongo password, the encryption password, provider API keys and the internal
 * token, and an agent-driven shell command must never see them. Before this
 * sealing, {@code ExecManager} left {@code ProcessBuilder.environment()}
 * untouched, so the subprocess inherited all of it — the finding behind
 * GitHub issue {@code mhus/vance#62}.
 *
 * <p>{@code HOME} is not the process home but the scope's own directory in the
 * homes tree ({@link HomesService}) — the fix for {@code mhus/vance#63}, where
 * every project and tenant on a pod shared one home.
 */
@Service
public class ExecEnvironmentBuilder {

    private final ExecProperties properties;
    private final HomesService homes;

    public ExecEnvironmentBuilder(ExecProperties properties, HomesService homes) {
        this.properties = properties;
        this.homes = homes;
    }

    /**
     * Returns the complete child environment for one job: sealed base, the
     * scoped {@code HOME}, then {@code extras} (script-run tokens and the like)
     * which may override base values — a script path that pins its own
     * {@code PATH} or {@code HOME} wins over the resolved one. {@code null}
     * extras mean base + home only.
     */
    public Map<String, String> build(
            String tenantId, String projectId, @Nullable String userId, @Nullable Map<String, String> extras) {
        Map<String, String> env =
                ExecEnvPolicy.seal(System.getenv(), properties.getEnv().getAllow());
        env.put("HOME", homes.ensure(tenantId, projectId, userId).toString());
        if (extras != null) {
            env.putAll(extras);
        }
        return env;
    }
}
