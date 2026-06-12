package de.mhus.vance.brain.tools.exec;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-submission options for {@link ExecManager}. Bundles the
 * cross-cutting params that grew out of script-execution needs so the
 * submit signatures stay readable.
 *
 * <p>{@code deadline}: hard-kill instant; watchdog kills the subprocess
 * and emits {@code EXEC_TIMEOUT} when reached. {@code null} = no
 * deadline.
 *
 * <p>{@code env}: sealed subprocess environment. {@code null} = inherit
 * the JVM env (legacy behaviour). Non-null wipes inherited vars and
 * installs only these — used by script-execution paths that mustn't
 * leak Brain creds.
 *
 * <p>{@code labels}: per-instance metadata for cross-cutting filters
 * (Cortex doc linkage, language, source). Convention keys live in
 * {@code planning/script-document-api.md} §4.5.
 */
public record SubmitOptions(
        @Nullable Instant deadline,
        @Nullable Map<String, String> env,
        Map<String, String> labels) {

    public SubmitOptions {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        env = env == null ? null : Map.copyOf(env);
    }

    public static SubmitOptions defaults() {
        return new SubmitOptions(null, null, Map.of());
    }

    public static SubmitOptions withDeadline(@Nullable Instant deadline) {
        return new SubmitOptions(deadline, null, Map.of());
    }

    public SubmitOptions withEnv(Map<String, String> env) {
        return new SubmitOptions(deadline, env, labels);
    }

    public SubmitOptions withLabels(Map<String, String> labels) {
        return new SubmitOptions(deadline, env, labels);
    }
}
