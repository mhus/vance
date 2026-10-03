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
 * <p>{@code env}: extra variables layered on top of the sealed base (see
 * {@link ExecEnvironmentBuilder}). {@code null} = base only. Used by
 * script-execution paths that pin their own {@code PATH}/{@code HOME} and
 * carry a {@code VANCE_TOKEN} — the base itself never contains Brain creds.
 *
 * <p>{@code labels}: per-instance metadata for cross-cutting filters
 * (Cortex doc linkage, language, source). Convention keys live in
 * {@code planning/script-document-api.md} §4.5.
 *
 * <p>{@code userId}: the acting user, carried so the environment builder can
 * resolve a {@code user}-scoped HOME ({@code HomesService}). {@code null} = no
 * user context; the home then falls back to the project scope.
 */
public record SubmitOptions(
        @Nullable Instant deadline,
        @Nullable Map<String, String> env,
        Map<String, String> labels,
        @Nullable String userId) {

    public SubmitOptions {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        env = env == null ? null : Map.copyOf(env);
    }

    public static SubmitOptions defaults() {
        return new SubmitOptions(null, null, Map.of(), null);
    }

    public static SubmitOptions withDeadline(@Nullable Instant deadline) {
        return new SubmitOptions(deadline, null, Map.of(), null);
    }

    public SubmitOptions withEnv(Map<String, String> env) {
        return new SubmitOptions(deadline, env, labels, userId);
    }

    public SubmitOptions withLabels(Map<String, String> labels) {
        return new SubmitOptions(deadline, env, labels, userId);
    }

    public SubmitOptions withUser(@Nullable String userId) {
        return new SubmitOptions(deadline, env, labels, userId);
    }
}
