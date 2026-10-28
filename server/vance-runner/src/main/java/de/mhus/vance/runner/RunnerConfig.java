package de.mhus.vance.runner;

import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the runner needs to know, from its environment (set by the deployment).
 *
 * @param runnerId       the name the brain addresses it by; the audience of its tokens
 * @param tenantId       the one tenant it serves (a runner per tenant, O-R1)
 * @param port           HTTP port
 * @param dataDir        the runner's volume: identity, later the workspaces
 * @param identityFile   the age identity file; created in {@code dataDir} when absent
 * @param tenantKeysFile PEM public keys of the tenant's JWT signing keys
 * @param maxRequestBytes upper bound for one request body
 */
public record RunnerConfig(
        String runnerId,
        String tenantId,
        int port,
        Path dataDir,
        Path identityFile,
        Path tenantKeysFile,
        long maxRequestBytes) {

    public static final int DEFAULT_PORT = 9840;
    public static final long DEFAULT_MAX_REQUEST_BYTES = 256L * 1024 * 1024;

    /**
     * Reads {@code VANCE_RUNNER_ID}, {@code VANCE_RUNNER_TENANT}, {@code VANCE_RUNNER_TENANT_KEYS}
     * (required) and {@code VANCE_RUNNER_PORT}, {@code VANCE_RUNNER_DATA_DIR},
     * {@code VANCE_RUNNER_IDENTITY_FILE}, {@code VANCE_RUNNER_MAX_REQUEST_BYTES}.
     *
     * @throws IllegalArgumentException naming the missing or malformed variable
     */
    public static RunnerConfig fromEnvironment(Map<String, String> env) {
        Path dataDir = Path.of(optional(env, "VANCE_RUNNER_DATA_DIR", "./data"));
        String identity = env.get("VANCE_RUNNER_IDENTITY_FILE");
        return new RunnerConfig(
                required(env, "VANCE_RUNNER_ID"),
                required(env, "VANCE_RUNNER_TENANT"),
                (int) number(env, "VANCE_RUNNER_PORT", DEFAULT_PORT),
                dataDir,
                identity == null || identity.isBlank() ? dataDir.resolve("runner.identity") : Path.of(identity),
                Path.of(required(env, "VANCE_RUNNER_TENANT_KEYS")),
                number(env, "VANCE_RUNNER_MAX_REQUEST_BYTES", DEFAULT_MAX_REQUEST_BYTES));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String optional(Map<String, String> env, String name, String fallback) {
        @Nullable String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long number(Map<String, String> env, String name, long fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number: " + value, e);
        }
    }
}
