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
 *
 * <p>What the child may reach when the launcher isolates it
 * ({@code vance.exec.isolation.mode=custom}): {@link #network} (off unless the
 * starter needs it — npm/pip registries, yt-dlp, a model download, or a daemon
 * the brain talks to over a socket), {@link #readablePaths} and
 * {@link #writablePaths} beyond its working directory and {@code HOME}. A bind
 * needs the path to exist, so a starter that creates its output passes the
 * directory, not the file.
 */
public record ProcessSpec(
        List<String> command,
        @Nullable Path workingDir,
        @Nullable Scope scope,
        Map<String, String> environment,
        boolean redirectErrorStream,
        boolean network,
        List<Path> readablePaths,
        List<Path> writablePaths) {

    public ProcessSpec {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
        readablePaths = List.copyOf(readablePaths);
        writablePaths = List.copyOf(writablePaths);
    }

    /** Tenant, project and optional user a child runs for. */
    public record Scope(
            String tenantId, String projectId, @Nullable String userId) {}

    /**
     * argv in {@code workingDir}, stderr merged, no scope, no extra environment,
     * no network, nothing visible beyond working directory and {@code HOME}.
     */
    public static ProcessSpec of(List<String> command, @Nullable Path workingDir) {
        return new ProcessSpec(command, workingDir, null, Map.of(), true, false, List.of(), List.of());
    }

    public ProcessSpec withScope(String tenantId, String projectId) {
        return new ProcessSpec(
                command,
                workingDir,
                new Scope(tenantId, projectId, null),
                environment,
                redirectErrorStream,
                network,
                readablePaths,
                writablePaths);
    }

    public ProcessSpec withEnvironment(Map<String, String> extra) {
        return new ProcessSpec(
                command, workingDir, scope, extra, redirectErrorStream, network, readablePaths, writablePaths);
    }

    public ProcessSpec withSeparateErrorStream() {
        return new ProcessSpec(command, workingDir, scope, environment, false, network, readablePaths, writablePaths);
    }

    /** The child may use the network (shares the brain's network namespace). */
    public ProcessSpec withNetwork() {
        return new ProcessSpec(
                command, workingDir, scope, environment, redirectErrorStream, true, readablePaths, writablePaths);
    }

    /** Existing paths the child may read. */
    public ProcessSpec withReadable(List<Path> paths) {
        return new ProcessSpec(
                command, workingDir, scope, environment, redirectErrorStream, network, paths, writablePaths);
    }

    /** Existing paths (files or directories) the child may write. */
    public ProcessSpec withWritable(List<Path> paths) {
        return new ProcessSpec(
                command, workingDir, scope, environment, redirectErrorStream, network, readablePaths, paths);
    }
}
