package de.mhus.vance.runner;

import de.mhus.vance.exec.EphemeralHandler;
import de.mhus.vance.exec.ExecEnvPolicy;
import de.mhus.vance.exec.ExecJob;
import de.mhus.vance.exec.ExecJobs;
import de.mhus.vance.exec.RootDirStore;
import de.mhus.vance.exec.TempHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the runner's file and exec operations share: the RootDir store and the exec
 * job table, both on the runner's volume, both from {@code vance-exec} — the same
 * code the brain's own workspace runs.
 *
 * <p>Layout under the data directory: {@code workspaces/} (RootDirs),
 * {@code exec/} (job logs), {@code homes/} (the scoped {@code HOME} of exec jobs).
 */
public final class RunnerWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunnerWorkspace.class);

    private static final int SOFT_LIMIT_PERCENT = 85;
    private static final int HARD_LIMIT_PERCENT = 95;
    private static final long KILL_GRACE_MS = 2_000;
    private static final int MAX_JOBS_PER_SCOPE = 100;
    private static final Duration ORPHAN_TTL = Duration.ofMinutes(10);

    private final RootDirStore store;
    private final ExecJobs jobs;
    private final Path homes;
    private final @Nullable String execWrapper;

    /**
     * @param execWrapper an isolation template for exec commands ({@code {workdir}} and
     *     {@code {cmd}} placeholders, see {@code ExecIsolation}), or {@code null} to run
     *     under the plain shell — the runner process itself is then the boundary
     */
    public RunnerWorkspace(Path dataDir, @Nullable String execWrapper) {
        this.store = new RootDirStore(
                () -> new RootDirStore.Settings(dataDir.resolve("workspaces"), SOFT_LIMIT_PERCENT, HARD_LIMIT_PERCENT),
                List.of(new TempHandler(), new EphemeralHandler()));
        this.jobs = new ExecJobs(
                new ExecJobs.Config(dataDir.resolve("exec"), KILL_GRACE_MS, MAX_JOBS_PER_SCOPE, ORPHAN_TTL),
                new LoggingListener());
        this.homes = dataDir.resolve("homes");
        this.execWrapper = execWrapper == null || execWrapper.isBlank() ? null : execWrapper.trim();
    }

    RootDirStore store() {
        return store;
    }

    ExecJobs jobs() {
        return jobs;
    }

    @Nullable
    String execWrapper() {
        return execWrapper;
    }

    /** The sealed child environment of a job: the inheritable base and a per-project {@code HOME}. */
    Map<String, String> execEnv(String tenantId, String projectId) throws IOException {
        Map<String, String> env = ExecEnvPolicy.seal(System.getenv(), List.of());
        Path home = homes.resolve(tenantId).resolve(projectId);
        Files.createDirectories(home);
        env.put("HOME", home.toString());
        return env;
    }

    @Override
    public void close() {
        jobs.close();
    }

    /** Terminal jobs need no mirroring here; the brain polls the runner. */
    private static final class LoggingListener implements ExecJobs.Listener {

        @Override
        public void stateChanged(ExecJob job) {
            log.debug("Exec job {} is now {}", job.id(), job.status());
        }

        @Override
        public void finished(ExecJob job) {
            log.debug("Exec job {} finished with {}", job.id(), job.exitCode());
        }

        @Override
        public void evicted(ExecJob job) {
            log.debug("Exec job {} dropped from the table", job.id());
        }
    }
}
