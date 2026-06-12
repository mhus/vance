package de.mhus.vance.brain.access;

import de.mhus.vance.shared.jwt.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the sealed subprocess environment for script-execution spawns
 * (Python today, future languages tomorrow). Each call allocates a
 * fresh {@code runId} and mints a {@link
 * de.mhus.vance.shared.jwt.TokenType#SCRIPT_RUN} JWT bound to it.
 *
 * <p>Subprocess sees only what we explicitly set — see
 * {@link de.mhus.vance.brain.tools.exec.ExecManager#runJob} for the
 * env-seal mechanism. The {@code VANCE_*} variables drive the
 * {@code vance.py} helper module; {@code PATH} / {@code HOME} /
 * {@code LANG} are filled with safe minimal defaults so common
 * Python subprocess uses (calling out to shell tools, pip caching)
 * keep working.
 *
 * <p>Token-life-cycle: bound to the run, not the TTL. The 24h
 * expiry is only a safety net for runs that get orphaned in the
 * registry; the primary revocation channel is
 * {@link de.mhus.vance.brain.execution.ExecutionRegistryService}
 * flipping the entry off {@code RUNNING}.
 */
@Service
@RequiredArgsConstructor
public class ScriptRunEnvironmentBuilder {

    private final JwtService jwtService;

    /**
     * Brain's local HTTP port. Defaults to 8080 (Spring Boot's default)
     * and is overridable for tests / non-standard deployments.
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Safety-net TTL on the script-run JWT. Independent of the registry
     * status which is the primary revocation channel.
     */
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    public ScriptRunEnvironment build(
            String tenantId,
            String projectId,
            @Nullable String sessionId,
            String username) {
        String runId = UUID.randomUUID().toString();
        String token = jwtService.createScriptRunToken(
                tenantId, username,
                runId, projectId, sessionId,
                Instant.now().plus(TOKEN_TTL));

        Map<String, String> env = new LinkedHashMap<>();
        env.put("VANCE_BRAIN_URL", "http://localhost:" + serverPort + "/brain/" + tenantId);
        env.put("VANCE_TENANT", tenantId);
        env.put("VANCE_PROJECT", projectId);
        if (sessionId != null && !sessionId.isBlank()) {
            env.put("VANCE_SESSION", sessionId);
        }
        env.put("VANCE_RUN_ID", runId);
        env.put("VANCE_TOKEN", token);
        // Minimal shell defaults so Python's subprocess module, pip
        // caches and common system-tool calls still function inside
        // the otherwise sealed env.
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("HOME", System.getProperty("user.home", "/tmp"));
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        return new ScriptRunEnvironment(runId, Map.copyOf(env));
    }

    public record ScriptRunEnvironment(String runId, Map<String, String> env) {}
}
