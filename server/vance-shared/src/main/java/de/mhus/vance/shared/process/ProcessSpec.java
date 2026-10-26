package de.mhus.vance.shared.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What to start: argv (never re-interpreted by a shell), working directory, the
 * tenant scope the child belongs to (decides its {@code HOME}; {@code null} for
 * starters that are not tied to a project), extra environment variables layered
 * on top of the sealed base, and whether stderr joins stdout.
 */
public record ProcessSpec(
        List<String> command,
        @Nullable Path workingDir,
        @Nullable Scope scope,
        Map<String, String> environment,
        boolean redirectErrorStream) {

    public ProcessSpec {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
    }

    /** Tenant, project and optional user a child runs for. */
    public record Scope(
            String tenantId, String projectId, @Nullable String userId) {}

    /** argv in {@code workingDir}, stderr merged, no scope, no extra environment. */
    public static ProcessSpec of(List<String> command, @Nullable Path workingDir) {
        return new ProcessSpec(command, workingDir, null, Map.of(), true);
    }

    public ProcessSpec withScope(String tenantId, String projectId) {
        return new ProcessSpec(
                command, workingDir, new Scope(tenantId, projectId, null), environment, redirectErrorStream);
    }

    public ProcessSpec withEnvironment(Map<String, String> extra) {
        return new ProcessSpec(command, workingDir, scope, extra, redirectErrorStream);
    }

    public ProcessSpec withSeparateErrorStream() {
        return new ProcessSpec(command, workingDir, scope, environment, false);
    }
}
