package de.mhus.vance.brain.vault;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-run collector of secret values a Python script pulls via
 * {@code vance.secret(...)} over the {@code /script/secret} REST endpoint, so the
 * exec-output renderer can mask them out of that run's stdout/stderr.
 *
 * <p>The pull (endpoint) and the render (a different thread, possibly a later
 * status poll) are in separate call chains, so — unlike the in-JVM JS path which
 * uses a thread-local tee — this is a static map keyed by the run id
 * ({@code cortex.runId} label / {@code VANCE_RUN_ID}). {@code peek} (not drain) is
 * used at render so repeated status polls stay masked; entries are evicted when
 * the run's registry entry is removed, with a hard size backstop against leaks.
 *
 * <p>Static (matching the existing {@code ACTIVE_*_TEE} masking hooks) so the
 * static {@code ExecJobRenderer} can reach it without threading it through the
 * exec subsystem's signatures.
 */
public final class ScriptSecretAccumulator {

    /** Backstop against unbounded growth if a run never gets its entry evicted. */
    static final int MAX_RUNS = 10_000;

    private static final Map<String, Set<String>> BY_RUN = new ConcurrentHashMap<>();

    private ScriptSecretAccumulator() {}

    /** Record a resolved value pulled by {@code runId}. No-op on blank/empty. */
    public static void record(String runId, String value) {
        if (runId == null || runId.isBlank() || value == null || value.isEmpty()) {
            return;
        }
        if (BY_RUN.size() >= MAX_RUNS && !BY_RUN.containsKey(runId)) {
            return;
        }
        BY_RUN.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(value);
    }

    /** Values pulled by {@code runId} (empty when none) — non-draining. */
    public static Set<String> peek(String runId) {
        if (runId == null) {
            return Set.of();
        }
        Set<String> values = BY_RUN.get(runId);
        return values == null ? Set.of() : values;
    }

    /** Drop the run's values — called when its exec registry entry is removed. */
    public static void evict(String runId) {
        if (runId != null) {
            BY_RUN.remove(runId);
        }
    }
}
